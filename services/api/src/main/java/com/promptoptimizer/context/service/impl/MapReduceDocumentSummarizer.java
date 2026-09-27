package com.promptoptimizer.context.service.impl;

import com.promptoptimizer.context.service.DocumentSummaryModel;
import com.promptoptimizer.context.service.DocumentSummaryModelException;
import com.promptoptimizer.context.service.DocumentUploadException;
import com.promptoptimizer.context.service.MapReduceSummaryOptions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 对全文索引执行有界 Map-Reduce 摘要，并在模型不可用时退回规则摘要。
 */
public class MapReduceDocumentSummarizer {

    private static final int FALLBACK_REPRESENTATIVE_CHUNKS = 5;
    private static final int MAX_REDUCE_ROUNDS = 32;

    private final FileContentSummarizer localSummarizer;
    private final Optional<DocumentSummaryModel> summaryModel;
    private final MapReduceSummaryOptions options;

    public MapReduceDocumentSummarizer(
            FileContentSummarizer localSummarizer,
            Optional<DocumentSummaryModel> summaryModel,
            MapReduceSummaryOptions options
    ) {
        this.localSummarizer = Objects.requireNonNull(localSummarizer, "localSummarizer must not be null");
        this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
    }

    /**
     * Map 阶段覆盖所有文本块，Reduce 阶段逐层合并中间摘要，始终限制单次模型输入和调用次数。
     */
    public SummaryReport summarize(SummarySource source, ProgressListener progressListener) {
        Objects.requireNonNull(source, "source must not be null");
        ProgressListener progress = progressListener == null ? ProgressListener.none() : progressListener;
        if (source.totalChunks() == 0) {
            progress.onProgress(1D);
            return new SummaryReport("", List.of(), 0, 0, false, "");
        }
        if (!options.enabled()) {
            return fallbackReport(source, progress, List.of());
        }
        if (summaryModel.isEmpty()) {
            return fallbackReport(
                    source,
                    progress,
                    List.of("Map-Reduce 摘要模型未装配，本次已使用本地规则摘要；全文索引不受影响。")
            );
        }

        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        List<String> mappedSummaries = new ArrayList<>();
        ModelCallState calls = new ModelCallState();
        int mappedChunks = 0;
        for (int offset = 0; offset < source.totalChunks(); offset += options.mapBatchSize()) {
            assertNotInterrupted();
            int requested = Math.min(options.mapBatchSize(), source.totalChunks() - offset);
            List<SourceChunk> chunks = source.reader().read(offset, requested);
            validateBatch(chunks, offset, requested);
            for (List<SourceChunk> group : partitionSourceChunks(chunks)) {
                mappedSummaries.add(mapGroup(source, group, calls, warnings));
            }
            mappedChunks += chunks.size();
            progress.onProgress(0.8D * mappedChunks / source.totalChunks());
        }

        String summary = reduce(source, mappedSummaries, calls, warnings, progress);
        progress.onProgress(1D);
        return new SummaryReport(
                limit(summary, options.finalSummaryCharacters()),
                List.copyOf(warnings),
                calls.totalCalls(),
                mappedChunks,
                calls.successfulCalls > 0,
                calls.lastModel
        );
    }

    /** 单批 Map 摘要受调用次数与输出长度保护；模型失败时退回本地规则摘要。 */
    private String mapGroup(
            SummarySource source,
            List<SourceChunk> chunks,
            ModelCallState calls,
            LinkedHashSet<String> warnings
    ) {
        if (calls.mapCalls >= options.maxMapCalls()) {
            warnings.add("Map 阶段达到模型调用保护上限，剩余文本块已使用本地规则摘要，未跳过全文索引内容。");
            return summarizeLocally(source, chunks, options.intermediateSummaryCharacters());
        }
        calls.mapCalls++;
        List<DocumentSummaryModel.SummaryPart> parts = chunks.stream()
                .map(chunk -> new DocumentSummaryModel.SummaryPart(
                        chunk.index(),
                        chunk.label(),
                        chunk.content()
                ))
                .toList();
        try {
            DocumentSummaryModel.SummaryResult result = summaryModel.orElseThrow().summarize(
                    new DocumentSummaryModel.SummaryRequest(
                            DocumentSummaryModel.SummaryStage.MAP,
                            source.path(),
                            source.language(),
                            parts,
                            options.intermediateSummaryCharacters()
                    )
            );
            calls.recordSuccess(result.model());
            return limit(result.summary(), options.intermediateSummaryCharacters());
        } catch (DocumentSummaryModelException exception) {
            warnings.add("Map 阶段部分模型请求失败，相关文本块已使用本地规则摘要，全文索引仍可正常检索。");
            return summarizeLocally(source, chunks, options.intermediateSummaryCharacters());
        }
    }

    /** 逐层归并中间摘要；当无法继续收敛或达到轮次上限时改用本地归并。 */
    private String reduce(
            SummarySource source,
            List<String> mappedSummaries,
            ModelCallState calls,
            LinkedHashSet<String> warnings,
            ProgressListener progress
    ) {
        List<String> level = mappedSummaries;
        boolean firstRound = true;
        int round = 0;
        while ((firstRound || level.size() > 1) && round < MAX_REDUCE_ROUNDS) {
            assertNotInterrupted();
            firstRound = false;
            List<List<String>> groups = partitionSummaries(level);
            List<String> nextLevel = new ArrayList<>(groups.size());
            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                boolean finalGroup = groups.size() == 1;
                int targetCharacters = finalGroup
                        ? options.finalSummaryCharacters()
                        : options.intermediateSummaryCharacters();
                nextLevel.add(reduceGroup(
                        source,
                        groups.get(groupIndex),
                        targetCharacters,
                        calls,
                        warnings
                ));
                double groupRatio = (double) (groupIndex + 1) / groups.size();
                progress.onProgress(Math.min(0.99D, 0.8D + Math.min(0.18D, round * 0.04D + groupRatio * 0.04D)));
            }
            if (nextLevel.size() >= level.size() && level.size() > 1) {
                warnings.add("Reduce 阶段无法继续缩减摘要层级，已使用本地规则完成最终归并。");
                return reduceLocally(source, nextLevel, options.finalSummaryCharacters());
            }
            level = nextLevel;
            round++;
        }
        if (level.size() > 1) {
            warnings.add("Reduce 阶段达到安全轮次上限，已使用本地规则完成最终归并。");
            return reduceLocally(source, level, options.finalSummaryCharacters());
        }
        return level.isEmpty() ? "" : level.get(0);
    }

    /** 对一组中间摘要执行 Reduce；模型不可用或超过预算时保持可用的本地结果。 */
    private String reduceGroup(
            SummarySource source,
            List<String> summaries,
            int targetCharacters,
            ModelCallState calls,
            LinkedHashSet<String> warnings
    ) {
        if (calls.reduceCalls >= options.maxReduceCalls()) {
            warnings.add("Reduce 阶段达到模型调用保护上限，剩余中间摘要已使用本地规则归并。");
            return reduceLocally(source, summaries, targetCharacters);
        }
        calls.reduceCalls++;
        List<DocumentSummaryModel.SummaryPart> parts = new ArrayList<>(summaries.size());
        for (int index = 0; index < summaries.size(); index++) {
            parts.add(new DocumentSummaryModel.SummaryPart(index, "第 " + (index + 1) + " 份中间摘要", summaries.get(index)));
        }
        try {
            DocumentSummaryModel.SummaryResult result = summaryModel.orElseThrow().summarize(
                    new DocumentSummaryModel.SummaryRequest(
                            DocumentSummaryModel.SummaryStage.REDUCE,
                            source.path(),
                            source.language(),
                            parts,
                            targetCharacters
                    )
            );
            calls.recordSuccess(result.model());
            return limit(result.summary(), targetCharacters);
        } catch (DocumentSummaryModelException exception) {
            warnings.add("Reduce 阶段模型请求失败，当前层级已使用本地规则归并，全文索引仍可正常检索。");
            return reduceLocally(source, summaries, targetCharacters);
        }
    }

    /** 模型摘要未启用或未装配时用分布式代表片段生成本地概览，并标记模型调用数为零。 */
    private SummaryReport fallbackReport(
            SummarySource source,
            ProgressListener progress,
            List<String> warnings
    ) {
        LinkedHashSet<Integer> indexes = representativeIndexes(
                source.totalChunks(),
                FALLBACK_REPRESENTATIVE_CHUNKS
        );
        List<SourceChunk> representativeChunks = new ArrayList<>(indexes.size());
        for (Integer index : indexes) {
            List<SourceChunk> chunks = source.reader().read(index, 1);
            if (!chunks.isEmpty()) {
                representativeChunks.add(chunks.get(0));
            }
        }
        progress.onProgress(1D);
        return new SummaryReport(
                summarizeLocally(source, representativeChunks, options.finalSummaryCharacters()),
                warnings,
                0,
                representativeChunks.size(),
                false,
                ""
        );
    }

    private String summarizeLocally(SummarySource source, List<SourceChunk> chunks, int maxCharacters) {
        StringBuilder content = new StringBuilder();
        for (SourceChunk chunk : chunks) {
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append("## ").append(chunk.label()).append('\n').append(chunk.content());
        }
        String summary = localSummarizer.summarize(source.path(), source.language(), content.toString());
        return limit(summary, maxCharacters);
    }

    private String reduceLocally(SummarySource source, List<String> summaries, int maxCharacters) {
        List<SourceChunk> chunks = new ArrayList<>(summaries.size());
        for (int index = 0; index < summaries.size(); index++) {
            chunks.add(new SourceChunk(index, "中间摘要 " + (index + 1), summaries.get(index)));
        }
        return summarizeLocally(source, chunks, maxCharacters);
    }

    /** 先拆分超过单批预算的原始块，再按字符预算重新分组，避免 Map 请求遗漏长块。 */
    private List<List<SourceChunk>> partitionSourceChunks(List<SourceChunk> chunks) {
        List<SourceChunk> expanded = new ArrayList<>();
        for (SourceChunk chunk : chunks) {
            if (chunk.content().length() <= options.maxBatchCharacters()) {
                expanded.add(chunk);
                continue;
            }
            int part = 1;
            for (int start = 0; start < chunk.content().length(); start += options.maxBatchCharacters()) {
                int end = Math.min(chunk.content().length(), start + options.maxBatchCharacters());
                expanded.add(new SourceChunk(
                        chunk.index(),
                        chunk.label() + "（分段 " + part++ + "）",
                        chunk.content().substring(start, end)
                ));
            }
        }

        List<List<SourceChunk>> groups = new ArrayList<>();
        List<SourceChunk> current = new ArrayList<>();
        int characters = 0;
        for (SourceChunk chunk : expanded) {
            if (!current.isEmpty() && characters + chunk.content().length() > options.maxBatchCharacters()) {
                groups.add(List.copyOf(current));
                current.clear();
                characters = 0;
            }
            current.add(chunk);
            characters += chunk.content().length();
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        return groups;
    }

    /** 同时按中间摘要数量和字符预算分组，避免单次 Reduce 请求过大。 */
    private List<List<String>> partitionSummaries(List<String> summaries) {
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int characters = 0;
        for (String summary : summaries) {
            boolean batchFull = current.size() >= options.reduceBatchSize();
            boolean characterLimitReached = !current.isEmpty()
                    && characters + summary.length() > options.maxBatchCharacters();
            if (batchFull || characterLimitReached) {
                groups.add(List.copyOf(current));
                current.clear();
                characters = 0;
            }
            current.add(summary);
            characters += summary.length();
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        return groups;
    }

    private void validateBatch(List<SourceChunk> chunks, int offset, int requested) {
        if (chunks == null || chunks.size() != requested) {
            throw new IllegalStateException("全文索引读取数量与摘要批次不一致");
        }
        for (int position = 0; position < chunks.size(); position++) {
            if (chunks.get(position).index() != offset + position) {
                throw new IllegalStateException("全文索引读取顺序与摘要批次不一致");
            }
        }
    }

    private LinkedHashSet<Integer> representativeIndexes(int chunkCount, int limit) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        int count = Math.min(limit, chunkCount);
        if (count == 1) {
            indexes.add(0);
            return indexes;
        }
        for (int position = 0; position < count; position++) {
            indexes.add(Math.round((float) position * (chunkCount - 1) / (count - 1)));
        }
        return indexes;
    }

    private String limit(String value, int maxCharacters) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxCharacters) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxCharacters - 1)).stripTrailing() + "…";
    }

    /** 响应取消信号，防止后台摘要任务继续消耗模型额度。 */
    private void assertNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new DocumentUploadException(
                    DocumentUploadException.Reason.CONFLICT,
                    "文档处理已取消"
            );
        }
    }

    /**
     * 按需读取有限数量的全文片段，供 Map 阶段分批处理。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface ChunkBatchReader {
        /** 按偏移读取指定数量的全文片段，返回结果须保持原始顺序。 */
        List<SourceChunk> read(int offset, int limit);
    }

    /**
     * 接收本次分层摘要的进度更新。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface ProgressListener {
        /** 发布 0 到 1 之间的摘要完成比例。 */
        void onProgress(double completionRatio);

        /** 返回无需处理进度事件的监听器。 */
        static ProgressListener none() {
            return completionRatio -> {
            };
        }
    }

    /**
     * 文档元数据和可分批读取的全文索引入口。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record SummarySource(
            String path,
            String language,
            int totalChunks,
            ChunkBatchReader reader
    ) {

        public SummarySource {
            path = path == null ? "" : path;
            language = language == null ? "text" : language;
            if (totalChunks < 0) {
                throw new IllegalArgumentException("全文索引块数量不能小于 0");
            }
            Objects.requireNonNull(reader, "reader must not be null");
        }
    }

    /**
     * 保留原始块序号、标签及正文的摘要输入片段。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record SourceChunk(
            int index,
            String label,
            String content
    ) {

        public SourceChunk {
            if (index < 0) {
                throw new IllegalArgumentException("文本块编号不能小于 0");
            }
            label = label == null || label.isBlank() ? "文档正文" : label;
            content = content == null ? "" : content;
        }
    }

    /**
     * 最终摘要及覆盖量、模型使用情况和降级警告。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record SummaryReport(
            String summary,
            List<String> warnings,
            int modelCalls,
            int mappedChunks,
            boolean modelAssisted,
            String model
    ) {

        public SummaryReport {
            summary = summary == null ? "" : summary;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            model = model == null ? "" : model;
        }
    }

    private static final class ModelCallState {

        private int mapCalls;
        private int reduceCalls;
        private int successfulCalls;
        private String lastModel = "";

        private void recordSuccess(String model) {
            successfulCalls++;
            if (model != null && !model.isBlank()) {
                lastModel = model;
            }
        }

        private int totalCalls() {
            return mapCalls + reduceCalls;
        }
    }
}
