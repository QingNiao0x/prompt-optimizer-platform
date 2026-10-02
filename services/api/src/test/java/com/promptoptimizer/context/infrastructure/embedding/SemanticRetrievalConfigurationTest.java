package com.promptoptimizer.context.infrastructure.embedding;

import com.promptoptimizer.context.service.impl.SemanticVectorIndex;
import com.promptoptimizer.context.service.TextEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证语义检索默认关闭时可安全启动，并拒绝不安全的向量端点协议。
 */
class SemanticRetrievalConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("app.provider.concurrency.store-mode=MEMORY")
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withUserConfiguration(SemanticRetrievalConfiguration.class);

    @Test
    void shouldRegisterDisabledSemanticIndexWithoutApiKey() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TextEmbeddingModel.class);
            assertThat(context).hasSingleBean(SemanticVectorIndex.class);
            assertThat(context).hasNotFailed();
        });
    }

    @Test
    void shouldRejectUnsupportedEmbeddingEndpointScheme() {
        contextRunner
                .withPropertyValues("app.retrieval.semantic.endpoint=file:///tmp/embeddings")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("http 或 https");
                });
    }
}
