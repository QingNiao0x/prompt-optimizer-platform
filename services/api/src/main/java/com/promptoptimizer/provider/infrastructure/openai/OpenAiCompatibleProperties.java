package com.promptoptimizer.provider.infrastructure.openai;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * OpenAI 兼容模型端点配置。
 *
 * <p>该配置仅在 {@code app.provider.mode=openai-compatible} 时加载。API Key 必须由环境变量或
 * 外部密钥管理服务注入，禁止写入源码和版本库。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "app.provider.openai-compatible")
public class OpenAiCompatibleProperties {

    @NotNull
    @NotBlank
    private String providerName = "deepseek";

    private URI endpoint = URI.create("https://api.deepseek.com/chat/completions");

    @NotBlank
    private String apiKey;

    @NotBlank
    private String model = "deepseek-chat";

    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private double temperature = 0.2D;

    @Min(1)
    @Max(32_768)
    private int maxTokens = 3_000;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(3);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(30);

    private boolean jsonResponseFormatEnabled = true;

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
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
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
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

    public boolean isJsonResponseFormatEnabled() {
        return jsonResponseFormatEnabled;
    }

    public void setJsonResponseFormatEnabled(boolean jsonResponseFormatEnabled) {
        this.jsonResponseFormatEnabled = jsonResponseFormatEnabled;
    }
}
