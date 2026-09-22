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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容模型端点配置。
 *
 * <p>该配置仅在 {@code app.provider.mode=openai-compatible} 时加载。API Key 必须由环境变量或
 * 外部密钥管理服务注入，禁止写入源码和版本库。默认仍支持旧的单供应商配置；启用多供应商后，
 * {@code providers} 中每条已配置密钥的路由都会进入同一个服务端模型目录。</p>
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

    private String apiKey;

    @NotBlank
    private String model = "deepseek-chat";

    /**
     * 允许用户选择的模型白名单。留空时只暴露默认模型；模型 ID 仍由服务端控制，
     * 不接受前端直接把任意字符串透传给上游端点。
     */
    private List<String> models = List.of();

    /** 是否启用按模型路由到不同 endpoint/API Key 的模式。 */
    private boolean multiProviderEnabled;

    /** 多供应商模式下的默认路由 key，例如 deepseek 或 tokenhub。 */
    private String defaultProvider = "";

    /** 多供应商路由配置。Map key 只作为服务端公开模型 ID 的前缀，不包含任何密钥。 */
    private Map<String, OpenAiCompatibleRouteProperties> providers = new LinkedHashMap<>();

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

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models == null ? List.of() : List.copyOf(models);
    }

    public boolean isMultiProviderEnabled() {
        return multiProviderEnabled;
    }

    public void setMultiProviderEnabled(boolean multiProviderEnabled) {
        this.multiProviderEnabled = multiProviderEnabled;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider == null ? "" : defaultProvider.trim();
    }

    public Map<String, OpenAiCompatibleRouteProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, OpenAiCompatibleRouteProperties> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
    }

    /**
     * 返回去重后的可选模型，并始终保证默认模型可以继续用于兼容旧请求。
     */
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

    /**
     * 返回当前服务端真正可用的供应商路由。未配置 API Key 的多供应商路由会被忽略，
     * 这样可以在同一份配置模板中安全保留尚未启用的供应商占位项。
     */
    public List<OpenAiCompatibleRoute> getConfiguredRoutes() {
        if (!multiProviderEnabled) {
            return List.of(new OpenAiCompatibleRoute(
                    "legacy",
                    normalized(providerName, "model"),
                    endpoint,
                    normalized(apiKey, ""),
                    normalized(model, ""),
                    getAvailableModels(),
                    true
            ));
        }

        List<OpenAiCompatibleRoute> routes = new ArrayList<>();
        if (providers == null) {
            return List.of();
        }
        providers.forEach((key, routeProperties) -> {
            if (routeProperties == null || isBlank(routeProperties.getApiKey())
                    || routeProperties.getEndpoint() == null || isBlank(routeProperties.getModel())) {
                return;
            }
            String routeKey = normalized(key, "");
            if (routeKey.isBlank()) {
                return;
            }
            routes.add(new OpenAiCompatibleRoute(
                    routeKey,
                    normalized(routeProperties.getProviderName(), routeKey),
                    routeProperties.getEndpoint(),
                    routeProperties.getApiKey().trim(),
                    routeProperties.getModel().trim(),
                    routeProperties.getAvailableModels(),
                    false
            ));
        });
        return List.copyOf(routes);
    }

    /**
     * 返回默认路由。多供应商模式优先使用 default-provider，否则使用第一条已配置路由。
     */
    public OpenAiCompatibleRoute getDefaultRoute() {
        List<OpenAiCompatibleRoute> routes = getConfiguredRoutes();
        if (routes.isEmpty()) {
            throw new IllegalStateException("至少需要配置一条可用的模型供应商路由");
        }
        if (multiProviderEnabled && !defaultProvider.isBlank()) {
            return routes.stream()
                    .filter(route -> route.key().equals(defaultProvider)
                            || route.providerName().equalsIgnoreCase(defaultProvider))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "默认模型供应商未配置或缺少 API Key：" + defaultProvider));
        }
        return routes.get(0);
    }

    /**
     * 将前端传入的模型目录 ID 解析为服务端路由和上游模型名。
     * 多供应商模式的公开 ID 为 {@code routeKey:modelName}，同时兼容唯一的裸模型名。
     */
    public ModelSelection resolveModel(String requestedModel) {
        List<OpenAiCompatibleRoute> routes = getConfiguredRoutes();
        OpenAiCompatibleRoute defaultRoute = getDefaultRoute();
        String requested = requestedModel == null ? "" : requestedModel.trim();
        if (requested.isBlank()) {
            return new ModelSelection(
                    defaultRoute,
                    defaultRoute.model(),
                    publicModelId(defaultRoute, defaultRoute.model())
            );
        }

        if (!multiProviderEnabled) {
            if (!defaultRoute.supportsModel(requested)) {
                throw new IllegalArgumentException("所选模型不可用");
            }
            return new ModelSelection(defaultRoute, requested, requested);
        }

        int separator = requested.indexOf(':');
        if (separator > 0 && separator < requested.length() - 1) {
            String routeKey = requested.substring(0, separator);
            String modelName = requested.substring(separator + 1);
            OpenAiCompatibleRoute route = routes.stream()
                    .filter(candidate -> candidate.key().equals(routeKey))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("所选模型供应商不可用"));
            if (!route.supportsModel(modelName)) {
                throw new IllegalArgumentException("所选模型不可用");
            }
            return new ModelSelection(route, modelName, requested);
        }

        List<OpenAiCompatibleRoute> matches = routes.stream()
                .filter(route -> route.supportsModel(requested))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("所选模型不可用或存在多个同名模型");
        }
        OpenAiCompatibleRoute route = matches.get(0);
        return new ModelSelection(route, requested, publicModelId(route, requested));
    }

    /**
     * 返回只包含公开模型信息的目录，不包含 endpoint、API Key 或其他运行时配置。
     */
    public List<ModelDescriptor> getAvailableModelDescriptors() {
        List<OpenAiCompatibleRoute> routes = getConfiguredRoutes();
        OpenAiCompatibleRoute defaultRoute = getDefaultRoute();
        List<ModelDescriptor> descriptors = new ArrayList<>();
        for (OpenAiCompatibleRoute route : routes) {
            for (String modelName : route.models()) {
                descriptors.add(new ModelDescriptor(
                        publicModelId(route, modelName),
                        route.providerName(),
                        modelName,
                        route.key().equals(defaultRoute.key()) && modelName.equals(defaultRoute.model())
                ));
            }
        }
        return List.copyOf(descriptors);
    }

    public String publicModelId(OpenAiCompatibleRoute route, String modelName) {
        return multiProviderEnabled ? route.key() + ":" + modelName : modelName;
    }

    private String normalized(String value, String fallback) {
        return value == null ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ModelSelection(OpenAiCompatibleRoute route, String model, String publicModelId) {
    }

    public record ModelDescriptor(String id, String provider, String model, boolean defaultModel) {
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
