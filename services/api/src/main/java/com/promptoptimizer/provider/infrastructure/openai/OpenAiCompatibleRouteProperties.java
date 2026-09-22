package com.promptoptimizer.provider.infrastructure.openai;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个 OpenAI 兼容供应商的可绑定配置。
 *
 * <p>该类型不包含任何默认密钥，未配置 API Key 的路由会被路由目录忽略。</p>
 */
public class OpenAiCompatibleRouteProperties {

    private String providerName;
    private URI endpoint;
    private String apiKey;
    private String model;
    private List<String> models = List.of();

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

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models == null ? List.of() : List.copyOf(models);
    }

    public List<String> getAvailableModels() {
        Set<String> available = new LinkedHashSet<>();
        if (models != null) {
            models.stream()
                    .map(value -> value == null ? "" : value.trim())
                    .filter(value -> !value.isBlank())
                    .forEach(available::add);
        }
        if (model != null && !model.isBlank()) {
            available.add(model.trim());
        }
        return List.copyOf(available);
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleRouteProperties[providerName='" + providerName
                + "', endpoint='" + endpoint
                + "', apiKeyConfigured=" + (apiKey != null && !apiKey.isBlank())
                + ", model='" + model
                + "', models=" + getAvailableModels() + "]";
    }
}
