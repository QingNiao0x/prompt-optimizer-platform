package com.promptoptimizer.provider.infrastructure.openai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatiblePropertiesTest {

    @Test
    void shouldExposeQualifiedModelsForConfiguredProvidersWithoutExposingKeys() {
        OpenAiCompatibleProperties properties = multiProviderProperties();

        assertThat(properties.getAvailableModelDescriptors())
                .extracting(OpenAiCompatibleProperties.ModelDescriptor::id)
                .containsExactly(
                        "deepseek:deepseek-chat",
                        "tokenhub:glm-5.3-flashx",
                        "tokenhub:kimi-k3"
                );
        assertThat(properties.getAvailableModelDescriptors())
                .extracting(OpenAiCompatibleProperties.ModelDescriptor::provider)
                .containsExactly("deepseek", "tokenhub", "tokenhub");
        assertThat(properties.getAvailableModelDescriptors())
                .extracting(OpenAiCompatibleProperties.ModelDescriptor::defaultModel)
                .containsExactly(true, false, false);
        assertThat(properties.getAvailableModelDescriptors().toString())
                .doesNotContain("deepseek-secret", "tokenhub-secret");
        assertThat(properties.getConfiguredRoutes().toString())
                .doesNotContain("deepseek-secret", "tokenhub-secret");
    }

    @Test
    void shouldResolveRequestedModelToItsOwnEndpointAndApiKey() {
        OpenAiCompatibleProperties properties = multiProviderProperties();

        OpenAiCompatibleProperties.ModelSelection selection = properties.resolveModel("tokenhub:kimi-k3");

        assertThat(selection.model()).isEqualTo("kimi-k3");
        assertThat(selection.publicModelId()).isEqualTo("tokenhub:kimi-k3");
        assertThat(selection.route().providerName()).isEqualTo("tokenhub");
        assertThat(selection.route().endpoint()).isEqualTo(URI.create("https://tokenhub.example.com/v1/chat/completions"));
        assertThat(selection.route().apiKey()).isEqualTo("tokenhub-secret");
    }

    @Test
    void shouldRejectAmbiguousBareModelInMultiProviderMode() {
        OpenAiCompatibleProperties properties = multiProviderProperties();
        OpenAiCompatibleRouteProperties tokenhub = properties.getProviders().get("tokenhub");
        tokenhub.setModels(List.of("deepseek-chat", "glm-5.3-flashx"));

        assertThatThrownBy(() -> properties.resolveModel("deepseek-chat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多个同名模型");
    }

    @Test
    void shouldKeepLegacySingleProviderModelIdsUnqualified() {
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setProviderName("deepseek");
        properties.setEndpoint(URI.create("https://deepseek.example.com/chat/completions"));
        properties.setApiKey("deepseek-secret");
        properties.setModel("deepseek-chat");
        properties.setModels(List.of("deepseek-chat"));

        assertThat(properties.getAvailableModelDescriptors())
                .extracting(OpenAiCompatibleProperties.ModelDescriptor::id)
                .containsExactly("deepseek-chat");
        assertThat(properties.resolveModel("deepseek-chat").publicModelId())
                .isEqualTo("deepseek-chat");
    }

    private OpenAiCompatibleProperties multiProviderProperties() {
        OpenAiCompatibleRouteProperties deepseek = route(
                "deepseek",
                "https://deepseek.example.com/chat/completions",
                "deepseek-secret",
                "deepseek-chat",
                List.of("deepseek-chat")
        );
        OpenAiCompatibleRouteProperties tokenhub = route(
                "tokenhub",
                "https://tokenhub.example.com/v1/chat/completions",
                "tokenhub-secret",
                "glm-5.3-flashx",
                List.of("glm-5.3-flashx", "kimi-k3")
        );
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setMultiProviderEnabled(true);
        properties.setDefaultProvider("deepseek");
        LinkedHashMap<String, OpenAiCompatibleRouteProperties> routes = new LinkedHashMap<>();
        routes.put("deepseek", deepseek);
        routes.put("tokenhub", tokenhub);
        properties.setProviders(routes);
        return properties;
    }

    private OpenAiCompatibleRouteProperties route(
            String provider,
            String endpoint,
            String apiKey,
            String model,
            List<String> models
    ) {
        OpenAiCompatibleRouteProperties properties = new OpenAiCompatibleRouteProperties();
        properties.setProviderName(provider);
        properties.setEndpoint(URI.create(endpoint));
        properties.setApiKey(apiKey);
        properties.setModel(model);
        properties.setModels(models);
        return properties;
    }
}
