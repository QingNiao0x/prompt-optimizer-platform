package com.promptoptimizer.context.infrastructure.embedding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 定义独立于聊天模型的语义向量检索配置，密钥只能由运行环境注入。
 */
@Validated
@ConfigurationProperties(prefix = "app.retrieval.semantic")
public class SemanticRetrievalProperties {

    private boolean enabled;

    @NotNull
    private URI endpoint = URI.create("http://localhost:11434/v1/embeddings");

    private String apiKey = "";

    @NotBlank
    @Size(max = 200)
    private String model = "embeddinggemma";

    @Min(1)
    @Max(64)
    private int batchSize = 16;

    @Min(6_000)
    @Max(500_000)
    private int maxBatchCharacters = 48_000;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(3);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(45);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(URI endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatchCharacters() {
        return maxBatchCharacters;
    }

    public void setMaxBatchCharacters(int maxBatchCharacters) {
        this.maxBatchCharacters = maxBatchCharacters;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
