package com.promptoptimizer.context.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证 Map-Reduce 摘要的全文覆盖、分层归并、调用保护和失败降级。
 */
class MapReduceDocumentSummarizerTest {

    @Test
    void shouldMapEveryChunkAndReduceAllIntermediateSummaries() {
        List<DocumentSummaryModel.SummaryRequest> requests = new ArrayList<>();
        DocumentSummaryModel model = request -> {
            requests.add(request);
            String content = request.parts().stream()
                    .map(DocumentSummaryModel.SummaryPart::content)
                    .reduce((left, right) -> left + " | " + right)
                    .orElseThrow();
            return new DocumentSummaryModel.SummaryResult(content, "summary-model");
        };
        MapReduceDocumentSummarizer summarizer = createSummarizer(model, options(true));
        List<MapReduceDocumentSummarizer.SourceChunk> chunks = sourceChunks(6);
        List<Double> progress = new ArrayList<>();

        MapReduceDocumentSummarizer.SummaryReport report = summarizer.summarize(
                source(chunks),
                progress::add
        );

        assertThat(requests.stream().filter(request -> request.stage() == DocumentSummaryModel.SummaryStage.MAP))
                .hasSize(3);
        assertThat(requests.stream().filter(request -> request.stage() == DocumentSummaryModel.SummaryStage.REDUCE))
                .hasSize(1);
        assertThat(report.summary()).contains("CHUNK-0").contains("CHUNK-5");
        assertThat(report.mappedChunks()).isEqualTo(6);
        assertThat(report.modelCalls()).isEqualTo(4);
        assertThat(report.modelAssisted()).isTrue();
        assertThat(report.model()).isEqualTo("summary-model");
        assertThat(progress).isNotEmpty();
        assertThat(progress.get(progress.size() - 1)).isEqualTo(1D);
    }

    @Test
    void shouldRecursivelyReduceUntilOnlyOneSummaryRemains() {
        List<DocumentSummaryModel.SummaryRequest> requests = new ArrayList<>();
        DocumentSummaryModel model = request -> {
            requests.add(request);
            String content = request.parts().stream()
                    .map(DocumentSummaryModel.SummaryPart::content)
                    .reduce((left, right) -> left + " | " + right)
                    .orElseThrow();
            return new DocumentSummaryModel.SummaryResult(content, "summary-model");
        };
        MapReduceSummaryOptions recursiveOptions = new MapReduceSummaryOptions(
                true,
                1,
                3,
                1_000,
                600,
                1_800,
                100,
                100
        );
        MapReduceDocumentSummarizer summarizer = createSummarizer(model, recursiveOptions);

        MapReduceDocumentSummarizer.SummaryReport report = summarizer.summarize(
                source(sourceChunks(10)),
                MapReduceDocumentSummarizer.ProgressListener.none()
        );

        assertThat(requests.stream().filter(request -> request.stage() == DocumentSummaryModel.SummaryStage.REDUCE).count())
                .isGreaterThan(1L);
        assertThat(report.summary()).contains("CHUNK-0").contains("CHUNK-9");
        assertThat(report.mappedChunks()).isEqualTo(10);
    }

    @Test
    void shouldFallbackWithoutLosingIndexWhenModelFails() {
        DocumentSummaryModel failingModel = request -> {
            throw new DocumentSummaryModelException("simulated failure");
        };
        MapReduceDocumentSummarizer summarizer = createSummarizer(failingModel, options(true));
        List<MapReduceDocumentSummarizer.SourceChunk> chunks = sourceChunks(4);

        MapReduceDocumentSummarizer.SummaryReport report = summarizer.summarize(
                source(chunks),
                MapReduceDocumentSummarizer.ProgressListener.none()
        );

        assertThat(report.summary()).isNotBlank();
        assertThat(report.mappedChunks()).isEqualTo(4);
        assertThat(report.modelAssisted()).isFalse();
        assertThat(report.warnings())
                .anyMatch(warning -> warning.contains("Map 阶段部分模型请求失败"))
                .anyMatch(warning -> warning.contains("Reduce 阶段模型请求失败"));
    }

    @Test
    void shouldUseRepresentativeRuleSummaryWhenFeatureIsDisabled() {
        DocumentSummaryModel unusedModel = request -> {
            throw new AssertionError("功能关闭时不应调用模型");
        };
        MapReduceDocumentSummarizer summarizer = createSummarizer(unusedModel, options(false));
        List<MapReduceDocumentSummarizer.SourceChunk> chunks = sourceChunks(10);

        MapReduceDocumentSummarizer.SummaryReport report = summarizer.summarize(
                source(chunks),
                MapReduceDocumentSummarizer.ProgressListener.none()
        );

        assertThat(report.summary()).isNotBlank();
        assertThat(report.modelCalls()).isZero();
        assertThat(report.mappedChunks()).isEqualTo(5);
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void shouldApplyMapCallLimitAndStillProcessEveryChunk() {
        DocumentSummaryModel model = request -> new DocumentSummaryModel.SummaryResult(
                request.parts().stream()
                        .map(DocumentSummaryModel.SummaryPart::content)
                        .reduce((left, right) -> left + " " + right)
                        .orElseThrow(),
                "summary-model"
        );
        MapReduceSummaryOptions limitedOptions = new MapReduceSummaryOptions(
                true,
                1,
                4,
                1_000,
                600,
                1_800,
                1,
                8
        );
        MapReduceDocumentSummarizer summarizer = createSummarizer(model, limitedOptions);
        List<MapReduceDocumentSummarizer.SourceChunk> chunks = sourceChunks(4);

        MapReduceDocumentSummarizer.SummaryReport report = summarizer.summarize(
                source(chunks),
                MapReduceDocumentSummarizer.ProgressListener.none()
        );

        assertThat(report.mappedChunks()).isEqualTo(4);
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("Map 阶段达到模型调用保护上限"));
    }

    private MapReduceDocumentSummarizer createSummarizer(
            DocumentSummaryModel model,
            MapReduceSummaryOptions summaryOptions
    ) {
        return new MapReduceDocumentSummarizer(
                new FileContentSummarizer(),
                Optional.of(model),
                summaryOptions
        );
    }

    private MapReduceSummaryOptions options(boolean enabled) {
        return new MapReduceSummaryOptions(
                enabled,
                2,
                3,
                1_000,
                600,
                1_800,
                100,
                100
        );
    }

    private MapReduceDocumentSummarizer.SummarySource source(
            List<MapReduceDocumentSummarizer.SourceChunk> chunks
    ) {
        return new MapReduceDocumentSummarizer.SummarySource(
                "docs/report.txt",
                "text",
                chunks.size(),
                (offset, limit) -> List.copyOf(chunks.subList(offset, Math.min(chunks.size(), offset + limit)))
        );
    }

    private List<MapReduceDocumentSummarizer.SourceChunk> sourceChunks(int count) {
        List<MapReduceDocumentSummarizer.SourceChunk> chunks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            chunks.add(new MapReduceDocumentSummarizer.SourceChunk(
                    index,
                    "章节 " + index,
                    "CHUNK-" + index + "：第 " + index + " 个章节的重要结论。"
            ));
        }
        return chunks;
    }
}
