package com.promptoptimizer.provider.infrastructure.openai;

import java.net.URI;
import java.util.List;

/**
 * 已解析的供应商路由。真实 API Key 只在服务端内存中存在，且不会出现在 {@link #toString()} 中。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class OpenAiCompatibleRoute {

    private final String key;
    private final String providerName;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final List<String> models;
    private final boolean legacy;

    public OpenAiCompatibleRoute(
            String key,
            String providerName,
            URI endpoint,
            String apiKey,
            String model,
            List<String> models,
            boolean legacy
    ) {
        this.key = key;
        this.providerName = providerName;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.models = List.copyOf(models);
        this.legacy = legacy;
    }

    public String key() {
        return key;
    }

    public String providerName() {
        return providerName;
    }

    public URI endpoint() {
        return endpoint;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public List<String> models() {
        return models;
    }

    public boolean legacy() {
        return legacy;
    }

    /** 仅接受该路由显式配置的模型名称，避免请求被转发到未授权模型。 */
    public boolean supportsModel(String requestedModel) {
        return models.contains(requestedModel);
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleRoute[key='" + key
                + "', providerName='" + providerName
                + "', endpoint='" + endpoint
                + "', model='" + model
                + "', models=" + models + "]";
    }
}
