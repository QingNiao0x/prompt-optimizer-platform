package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.application.DocumentSummaryModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * OpenAI 兼容 Provider 的 Spring 装配配置。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "openai-compatible")
@EnableConfigurationProperties(OpenAiCompatibleProperties.class)
public class OpenAiCompatibleProviderConfiguration {

    private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https");

    /**
     * 创建带连接和读取超时的 RestClient。
     */
    @Bean
    RestClient openAiCompatibleRestClient(
            RestClient.Builder builder,
            OpenAiCompatibleProperties properties
    ) {
        validateRoutes(properties);
        validateTimeout("connect-timeout", properties.getConnectTimeout());
        validateTimeout("read-timeout", properties.getReadTimeout());

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.requestFactory(requestFactory).build();
    }

    /**
     * 注册 OpenAI 兼容 Provider 实现。
     */
    @Bean
    OpenAiCompatiblePromptEnhancementProvider openAiCompatiblePromptEnhancementProvider(
            RestClient openAiCompatibleRestClient,
            ObjectMapper objectMapper,
            OpenAiCompatibleProperties properties
    ) {
        return new OpenAiCompatiblePromptEnhancementProvider(
                openAiCompatibleRestClient,
                objectMapper,
                properties
        );
    }

    /**
     * 复用当前聊天模型、端点和运行环境密钥，为大型文档提供 Map-Reduce 摘要能力。
     */
    @Bean
    DocumentSummaryModel openAiCompatibleDocumentSummaryModel(
            RestClient openAiCompatibleRestClient,
            ObjectMapper objectMapper,
            OpenAiCompatibleProperties properties
    ) {
        return new OpenAiCompatibleDocumentSummaryModel(
                openAiCompatibleRestClient,
                objectMapper,
                properties
        );
    }

    /**
     * 校验模型端点协议。
     */
    private void validateEndpoint(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalStateException("模型端点不能为空");
        }
        if (endpoint.getScheme() == null || !SUPPORTED_SCHEMES.contains(endpoint.getScheme().toLowerCase())) {
            throw new IllegalStateException("模型端点仅支持 http 或 https 协议");
        }
    }

    /**
     * 在创建 HTTP 客户端前校验所有已启用路由，避免请求运行到一半才发现密钥或 endpoint 缺失。
     */
    private void validateRoutes(OpenAiCompatibleProperties properties) {
        var routes = properties.getConfiguredRoutes();
        if (routes.isEmpty()) {
            throw new IllegalStateException("至少需要配置一条可用的模型供应商路由");
        }
        routes.forEach(route -> {
            validateEndpoint(route.endpoint());
            if (route.apiKey() == null || route.apiKey().isBlank()) {
                throw new IllegalStateException("模型供应商 API Key 未配置：" + route.providerName());
            }
            if (route.model() == null || route.model().isBlank()) {
                throw new IllegalStateException("模型默认名称未配置：" + route.providerName());
            }
        });
        // 触发 default-provider 的存在性校验，同时保持错误在启动阶段暴露。
        properties.getDefaultRoute();
    }

    /**
     * 校验超时配置必须大于 0。
     */
    private void validateTimeout(String propertyName, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException(propertyName + " 必须大于 0");
        }
    }
}
