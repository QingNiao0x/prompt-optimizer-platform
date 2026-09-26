package com.promptoptimizer.context.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证语义向量索引的分批构建、余弦召回和失败降级行为。
 */
class SemanticVectorIndexTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void shouldFindSemanticMatchWhenQueryUsesDifferentWords() {
        List<String> chunks = List.of(
                "员工入职与考勤管理流程",
                "机动车制动系统检修和零部件更换说明",
                "季度财务预算审批制度"
        );
        SemanticVectorIndex index = createEnabledIndex(inputs -> embeddingBatch(inputs, false));
        List<Integer> progress = new ArrayList<>();

        SemanticVectorIndex.BuildReport buildReport = index.build(
                temporaryDirectory.resolve("vectors.data"),
                chunks.size(),
                (offset, limit) -> chunks.subList(offset, Math.min(chunks.size(), offset + limit)),
                progress::add
        );
        SemanticVectorIndex.SearchResult result = index.search(
                temporaryDirectory.resolve("vectors.data"),
                "汽车维修保养",
                2
        );

        assertThat(buildReport.built()).isTrue();
        assertThat(buildReport.indexedChunks()).isEqualTo(3);
        assertThat(progress).containsExactly(2, 3);
        assertThat(result.used()).isTrue();
        assertThat(result.scores().get(1)).isEqualTo(1D);
        assertThat(result.scores().get(0)).isLessThan(result.scores().get(1));
    }

    @Test
    void shouldReturnFallbackWarningAndRemovePartialIndexWhenEmbeddingFails() {
        SemanticVectorIndex index = createEnabledIndex(inputs -> {
            throw new IllegalStateException("simulated provider failure");
        });
        Path indexFile = temporaryDirectory.resolve("failed-vectors.data");

        SemanticVectorIndex.BuildReport result = index.build(
                indexFile,
                1,
                (offset, limit) -> List.of("正文"),
                ignored -> {
                }
        );

        assertThat(result.built()).isFalse();
        assertThat(result.warning()).contains("关键词检索");
        assertThat(indexFile).doesNotExist();
    }

    @Test
    void shouldNotCallEmbeddingModelWhenSemanticRetrievalIsDisabled() {
        TextEmbeddingModel model = inputs -> {
            throw new AssertionError("disabled index must not call embedding model");
        };
        SemanticVectorIndex index = new SemanticVectorIndex(
                model,
                new SemanticVectorIndexOptions(false, 2, 12_000)
        );

        SemanticVectorIndex.BuildReport build = index.build(
                temporaryDirectory.resolve("disabled.data"),
                1,
                (offset, limit) -> List.of("正文"),
                ignored -> {
                }
        );
        SemanticVectorIndex.SearchResult search = index.search(
                temporaryDirectory.resolve("disabled.data"),
                "查询",
                1
        );

        assertThat(build.built()).isFalse();
        assertThat(build.warning()).isEmpty();
        assertThat(search.used()).isFalse();
        assertThat(search.warning()).isEmpty();
    }

    private SemanticVectorIndex createEnabledIndex(TextEmbeddingModel model) {
        return new SemanticVectorIndex(
                model,
                new SemanticVectorIndexOptions(true, 2, 12_000)
        );
    }

    private TextEmbeddingModel.EmbeddingBatch embeddingBatch(List<String> inputs, boolean ignored) {
        List<float[]> vectors = inputs.stream()
                .map(value -> {
                    if (value.contains("汽车维修保养") || value.contains("机动车制动系统")) {
                        return new float[]{1F, 0F, 0F};
                    }
                    if (value.contains("财务预算")) {
                        return new float[]{0F, 1F, 0F};
                    }
                    return new float[]{0F, 0F, 1F};
                })
                .toList();
        return new TextEmbeddingModel.EmbeddingBatch("test-model", vectors);
    }
}
