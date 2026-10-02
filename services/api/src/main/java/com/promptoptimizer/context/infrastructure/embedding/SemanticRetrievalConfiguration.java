package com.promptoptimizer.context.infrastructure.embedding;

import com.promptoptimizer.context.service.impl.SemanticVectorIndex;
import com.promptoptimizer.context.service.SemanticVectorIndexOptions;
import com.promptoptimizer.context.service.TextEmbeddingModel;
import com.promptoptimizer.provider.infrastructure.concurrency.ModelConcurrencyConfiguration;
import com.promptoptimizer.provider.infrastructure.concurrency.ModelConcurrencyHttpInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 装配语义向量模型和磁盘索引模块，且不与聊天模型配置或密钥耦合。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SemanticRetrievalProperties.class)
@Import(ModelConcurrencyConfiguration.class)
public class SemanticRetrievalConfiguration {

    private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https");

    @Bean("semanticEmbeddingRestClient")
    RestClient semanticEmbeddingRestClient(
            RestClient.Builder builder,
            SemanticRetrievalProperties properties,
            ModelConcurrencyHttpInterceptor concurrencyInterceptor
    ) {
        validateEndpoint(properties.getEndpoint());
        validateTimeout("connect-timeout", properties.getConnectTimeout());
        validateTimeout("read-timeout", properties.getReadTimeout());

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.clone().requestFactory(requestFactory)
                .requestInterceptor(concurrencyInterceptor).build();
    }

    @Bean
    TextEmbeddingModel textEmbeddingModel(
            @Qualifier("semanticEmbeddingRestClient") RestClient restClient,
            SemanticRetrievalProperties properties
    ) {
        return new OpenAiCompatibleTextEmbeddingModel(restClient, properties);
    }

    @Bean
    SemanticVectorIndex semanticVectorIndex(
            TextEmbeddingModel embeddingModel,
            SemanticRetrievalProperties properties
    ) {
        SemanticVectorIndexOptions options = new SemanticVectorIndexOptions(
                properties.isEnabled(),
                properties.getBatchSize(),
                properties.getMaxBatchCharacters()
        );
        return new SemanticVectorIndex(embeddingModel, options);
    }

    private void validateEndpoint(URI endpoint) {
        if (endpoint.getScheme() == null
                || !SUPPORTED_SCHEMES.contains(endpoint.getScheme().toLowerCase())) {
            throw new IllegalStateException("向量模型端点仅支持 http 或 https 协议");
        }
    }

    private void validateTimeout(String propertyName, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException(propertyName + " 必须大于 0");
        }
    }
}
