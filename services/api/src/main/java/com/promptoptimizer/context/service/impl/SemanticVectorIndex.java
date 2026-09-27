package com.promptoptimizer.context.service.impl;

import com.promptoptimizer.context.service.SemanticVectorIndexOptions;
import com.promptoptimizer.context.service.TextEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.IntConsumer;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 将文档片段向量写入临时磁盘索引，并通过余弦相似度召回语义相关片段。
 */
public class SemanticVectorIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticVectorIndex.class);
    private static final int FILE_MAGIC = 0x514E5658;
    private static final int FILE_VERSION = 1;
    private static final int MAX_VECTOR_DIMENSIONS = 16_384;
    private static final int MAX_MODEL_NAME_CHARACTERS = 512;
    private static final String BUILD_FALLBACK_WARNING = "语义向量索引构建失败，已继续使用关键词检索。";
    private static final String SEARCH_FALLBACK_WARNING = "语义检索暂时不可用，本次已使用关键词检索。";

    private final TextEmbeddingModel embeddingModel;
    private final SemanticVectorIndexOptions options;

    public SemanticVectorIndex(
            TextEmbeddingModel embeddingModel,
            SemanticVectorIndexOptions options
    ) {
        this.embeddingModel = embeddingModel;
        this.options = options;
    }

    /**
     * 分批读取文档片段并生成磁盘向量索引。失败时删除半成品，让调用方安全退回关键词检索。
     */
    public BuildReport build(
            Path indexFile,
            int chunkCount,
            ChunkBatchSource source,
            IntConsumer progressConsumer
    ) {
        if (!options.enabled() || chunkCount <= 0) {
            return BuildReport.skipped();
        }

        Path pendingFile = indexFile.resolveSibling(indexFile.getFileName() + ".part");
        deleteQuietly(pendingFile);
        deleteQuietly(indexFile);
        try {
            int offset = 0;
            int dimensions = 0;
            String indexedModel = "";
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(pendingFile)))) {
                while (offset < chunkCount) {
                    List<String> batch = loadBoundedBatch(source, offset, chunkCount - offset);
                    TextEmbeddingModel.EmbeddingBatch embedded = embeddingModel.embed(batch);
                    validateBatch(embedded, batch.size(), dimensions, indexedModel);
                    if (offset == 0) {
                        dimensions = embedded.vectors().getFirst().length;
                        indexedModel = embedded.model();
                        writeHeader(output, indexedModel, dimensions, chunkCount);
                    }
                    for (float[] vector : embedded.vectors()) {
                        writeNormalizedVector(output, vector);
                    }
                    offset += batch.size();
                    progressConsumer.accept(offset);
                }
            }
            moveCompletedIndex(pendingFile, indexFile);
            return new BuildReport(true, chunkCount, dimensions, indexedModel, "");
        } catch (RuntimeException | IOException exception) {
            deleteQuietly(pendingFile);
            deleteQuietly(indexFile);
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("语义向量索引任务已取消", exception);
            }
            LOGGER.warn("语义向量索引构建失败，已降级为关键词检索：{}", exception.getClass().getSimpleName());
            return new BuildReport(false, 0, 0, "", BUILD_FALLBACK_WARNING);
        }
    }

    /**
     * 为查询生成一次向量，并以常量级堆内存扫描磁盘索引，返回最相关片段及相似度。
     */
    public SearchResult search(Path indexFile, String query, int limit) {
        if (!options.enabled() || query == null || query.isBlank() || limit <= 0 || !Files.isRegularFile(indexFile)) {
            return SearchResult.notUsed();
        }

        try {
            IndexHeader header = readHeader(indexFile);
            TextEmbeddingModel.EmbeddingBatch queryBatch = embeddingModel.embed(List.of(query));
            validateBatch(queryBatch, 1, header.dimensions(), header.model());
            float[] queryVector = normalize(queryBatch.vectors().getFirst());
            Map<Integer, Double> scores = scanTopMatches(indexFile, header, queryVector, limit);
            return new SearchResult(true, scores, "");
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("语义向量查询失败，已降级为关键词检索：{}", exception.getClass().getSimpleName());
            return new SearchResult(false, Map.of(), SEARCH_FALLBACK_WARNING);
        }
    }

    /** 限制单批片段数量和字符数；超长首段裁剪后仍保证至少有一段可送向量模型。 */
    private List<String> loadBoundedBatch(
            ChunkBatchSource source,
            int offset,
            int remainingChunks
    ) {
        int requested = Math.min(options.batchSize(), remainingChunks);
        List<String> candidates = source.read(offset, requested);
        if (candidates == null || candidates.isEmpty() || candidates.size() > requested) {
            throw new IllegalStateException("语义索引片段读取结果无效");
        }

        List<String> batch = new ArrayList<>(candidates.size());
        int characters = 0;
        for (String candidate : candidates) {
            String value = candidate == null || candidate.isBlank() ? "[空白片段]" : candidate;
            if (batch.isEmpty() && value.length() > options.maxBatchCharacters()) {
                value = value.substring(0, options.maxBatchCharacters());
            }
            if (!batch.isEmpty() && characters + value.length() > options.maxBatchCharacters()) {
                break;
            }
            batch.add(value);
            characters += value.length();
        }
        if (batch.isEmpty()) {
            throw new IllegalStateException("语义索引批次不能为空");
        }
        return batch;
    }

    /** 校验向量数量、维度与模型一致性，防止损坏的批次写入磁盘索引。 */
    private void validateBatch(
            TextEmbeddingModel.EmbeddingBatch batch,
            int expectedSize,
            int expectedDimensions,
            String expectedModel
    ) {
        if (batch.vectors().size() != expectedSize) {
            throw new IllegalStateException("向量模型返回数量与输入数量不一致");
        }
        if (batch.model().length() > MAX_MODEL_NAME_CHARACTERS) {
            throw new IllegalStateException("向量模型名称过长");
        }
        if (!expectedModel.isBlank() && !expectedModel.equals(batch.model())) {
            throw new IllegalStateException("向量模型在同一索引任务中发生变化");
        }
        int dimensions = expectedDimensions == 0 ? batch.vectors().getFirst().length : expectedDimensions;
        if (dimensions <= 0 || dimensions > MAX_VECTOR_DIMENSIONS) {
            throw new IllegalStateException("向量维度无效");
        }
        for (float[] vector : batch.vectors()) {
            if (vector.length != dimensions) {
                throw new IllegalStateException("向量维度不一致");
            }
            validateFinite(vector);
        }
    }

    private void validateFinite(float[] vector) {
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("向量包含非有限数值");
            }
        }
    }

    private void writeHeader(
            DataOutputStream output,
            String model,
            int dimensions,
            int chunkCount
    ) throws IOException {
        output.writeInt(FILE_MAGIC);
        output.writeInt(FILE_VERSION);
        output.writeUTF(model);
        output.writeInt(dimensions);
        output.writeInt(chunkCount);
    }

    private void writeNormalizedVector(DataOutputStream output, float[] vector) throws IOException {
        float[] normalized = normalize(vector);
        for (float value : normalized) {
            output.writeFloat(value);
        }
    }

    /** 单位化向量，使后续点积可用于比较余弦相似度。 */
    private float[] normalize(float[] vector) {
        double squaredLength = 0D;
        for (float value : vector) {
            squaredLength += (double) value * value;
        }
        if (squaredLength <= 0D) {
            throw new IllegalStateException("向量长度不能为 0");
        }
        double length = Math.sqrt(squaredLength);
        float[] normalized = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            normalized[index] = (float) (vector[index] / length);
        }
        return normalized;
    }

    /** 读取磁盘索引头并验证格式标记、模型、维度与块数量。 */
    private IndexHeader readHeader(Path indexFile) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(indexFile)))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != FILE_MAGIC || version != FILE_VERSION) {
                throw new IOException("语义向量索引格式不兼容");
            }
            String model = input.readUTF();
            int dimensions = input.readInt();
            int chunkCount = input.readInt();
            if (model.isBlank() || dimensions <= 0 || chunkCount <= 0) {
                throw new IOException("语义向量索引头无效");
            }
            return new IndexHeader(model, dimensions, chunkCount);
        }
    }

    /** 顺序扫描磁盘向量并用有界优先队列保留最高分片段，避免加载整个索引。 */
    private Map<Integer, Double> scanTopMatches(
            Path indexFile,
            IndexHeader expectedHeader,
            float[] queryVector,
            int limit
    ) throws IOException {
        int boundedLimit = Math.min(limit, expectedHeader.chunkCount());
        PriorityQueue<SemanticMatch> topMatches = new PriorityQueue<>(
                Comparator.comparingDouble(SemanticMatch::score)
                        .thenComparing(Comparator.comparingInt(SemanticMatch::chunkIndex).reversed())
        );
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(indexFile)))) {
            IndexHeader actualHeader = readHeader(input);
            if (!expectedHeader.equals(actualHeader) || queryVector.length != actualHeader.dimensions()) {
                throw new IOException("语义查询向量与索引不兼容");
            }
            for (int chunkIndex = 0; chunkIndex < actualHeader.chunkCount(); chunkIndex++) {
                double score = 0D;
                for (int dimension = 0; dimension < actualHeader.dimensions(); dimension++) {
                    score += queryVector[dimension] * input.readFloat();
                }
                SemanticMatch match = new SemanticMatch(chunkIndex, Math.max(0D, Math.min(1D, score)));
                if (topMatches.size() < boundedLimit) {
                    topMatches.add(match);
                } else if (compareMatch(match, topMatches.peek()) > 0) {
                    topMatches.poll();
                    topMatches.add(match);
                }
            }
            if (input.read() != -1) {
                throw new IOException("语义向量索引包含多余数据");
            }
        } catch (EOFException exception) {
            throw new IOException("语义向量索引不完整", exception);
        }

        List<SemanticMatch> ordered = new ArrayList<>(topMatches);
        ordered.sort(Comparator.comparingDouble(SemanticMatch::score).reversed()
                .thenComparingInt(SemanticMatch::chunkIndex));
        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (SemanticMatch match : ordered) {
            scores.put(match.chunkIndex(), match.score());
        }
        return Collections.unmodifiableMap(scores);
    }

    private IndexHeader readHeader(DataInputStream input) throws IOException {
        int magic = input.readInt();
        int version = input.readInt();
        if (magic != FILE_MAGIC || version != FILE_VERSION) {
            throw new IOException("语义向量索引格式不兼容");
        }
        return new IndexHeader(input.readUTF(), input.readInt(), input.readInt());
    }

    private int compareMatch(SemanticMatch left, SemanticMatch right) {
        int scoreComparison = Double.compare(left.score(), right.score());
        return scoreComparison != 0
                ? scoreComparison
                : Integer.compare(right.chunkIndex(), left.chunkIndex());
    }

    private void moveCompletedIndex(Path pendingFile, Path indexFile) throws IOException {
        try {
            Files.move(
                    pendingFile,
                    indexFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(pendingFile, indexFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 清理失败或取消后的临时向量文件，不让清理异常覆盖原始错误。 */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 半成品会随所属文档会话到期再次清理，不能覆盖原始处理结果。
        }
    }

    /**
     * 为避免一次性把全文装入堆内存，由调用方按偏移提供有限片段批次。
     */
    @FunctionalInterface
    public interface ChunkBatchSource {

        /** 按偏移返回有限个原始片段，顺序须与待建立索引的编号一致。 */
        List<String> read(int offset, int limit);
    }

    /**
     * 索引构建结果。warning 为空表示未发生可见降级。
     */
    public record BuildReport(
            boolean built,
            int indexedChunks,
            int dimensions,
            String model,
            String warning
    ) {

        private static BuildReport skipped() {
            return new BuildReport(false, 0, 0, "", "");
        }
    }

    /**
     * 查询结果。scores 的键是文档块编号，值是 0 到 1 的余弦相似度。
     */
    public record SearchResult(boolean used, Map<Integer, Double> scores, String warning) {

        public SearchResult {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
            warning = warning == null ? "" : warning;
        }

        private static SearchResult notUsed() {
            return new SearchResult(false, Map.of(), "");
        }
    }

    private record IndexHeader(String model, int dimensions, int chunkCount) {
    }

    private record SemanticMatch(int chunkIndex, double score) {
    }
}
