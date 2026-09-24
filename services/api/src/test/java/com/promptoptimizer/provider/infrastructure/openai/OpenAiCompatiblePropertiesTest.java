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
                        "tokenhub:deepseek/deepseek-flash",
                        "tokenhub:deepseek-v4-pro-0813",
                        "tokenhub:kimi-k3",
                        "tokenhub:kimi-k2.8-preview",
                        "tokenhub:kimi-k2.7-code",
                        "tokenhub:glm-5.3",
                        "tokenhub:glm-5.3-flashx",
                        "tokenhub:hy4-preview",
                        "tokenhub:hy3",
                        "tokenhub:minimax-m3"
                );
        assertThat(properties.getAvailableModelDescriptors())
                .extracting(OpenAiCompatibleProperties.ModelDescriptor::provider)
                .containsExactly(
                        "deepseek",
                        "tokenhub",
                        "tokenhub",
                        "tokenhub",
                        "tokenhub",
                        "tokenhub",
                        "tokenhub",
                        "tokenhub",
                        "tokenhub",
                        "tokenhub",
                        "tokenhub"
                );
        assertThat(properties.getAvailableModelDescriptors())
                .extracting(OpenAiCompatibleProperties.ModelDescriptor::defaultModel)
                .containsExactly(true, false, false, false, false, false, false, false, false, false, false);
        assertThat(properties.getAvailableModelDescriptors().toString())
                .doesNotContain("deepseek-secret", "tokenhub-secret");
        assertThat(properties.getConfiguredRoutes().toString())
                .doesNotContain("deepseek-secret", "tokenhub-secret");
    }

    @Test
    void shouldKeepRequestedTokenHubModelsWhenEnvironmentListContainsOnlyOlderModels() {
        OpenAiCompatibleProperties properties = multiProviderProperties();

        assertThat(properties.getAvailableModelDescriptors())
                .extracting(OpenAiCompatibleProperties.ModelDescriptor::id)
                .containsExactly(
                        "deepseek:deepseek-chat",
                        "tokenhub:deepseek/deepseek-flash",
                        "tokenhub:deepseek-v4-pro-0813",
                        "tokenhub:kimi-k3",
                        "tokenhub:kimi-k2.8-preview",
                        "tokenhub:kimi-k2.7-code",
                        "tokenhub:glm-5.3",
                        "tokenhub:glm-5.3-flashx",
                        "tokenhub:hy4-preview",
                        "tokenhub:hy3",
                        "tokenhub:minimax-m3"
                );

        OpenAiCompatibleProperties.ModelSelection selection = properties.resolveModel("tokenhub:hy4-preview");
        assertThat(selection.model()).isEqualTo("hy4-preview");
        assertThat(selection.route().providerName()).isEqualTo("tokenhub");
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
    void shouldHideRetiredModelAndFallBackToASelectableProvider() {
        OpenAiCompatibleProperties properties = multiProviderProperties();
        properties.setModelCatalogHiddenIds(List.of("deepseek:deepseek-chat"));

        assertThat(properties.getAvailableModelDescriptors())
                .extracting(OpenAiCompatibleProperties.ModelDescriptor::id)
                .containsExactly(
                        "tokenhub:deepseek/deepseek-flash",
                        "tokenhub:deepseek-v4-pro-0813",
                        "tokenhub:kimi-k3",
                        "tokenhub:kimi-k2.8-preview",
                        "tokenhub:kimi-k2.7-code",
                        "tokenhub:glm-5.3",
                        "tokenhub:glm-5.3-flashx",
                        "tokenhub:hy4-preview",
                        "tokenhub:hy3",
                        "tokenhub:minimax-m3"
                );
        assertThat(properties.getDefaultRoute().key()).isEqualTo("tokenhub");
        assertThat(properties.resolveModel(null).publicModelId()).isEqualTo("tokenhub:glm-5.3-flashx");
        assertThatThrownBy(() -> properties.resolveModel("deepseek:deepseek-chat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("所选模型不可用");
    }

    @Test
    void shouldResolveTokenHubModelIdsContainingSlash() {
        OpenAiCompatibleProperties properties = multiProviderProperties();
        OpenAiCompatibleRouteProperties tokenhub = properties.getProviders().get("tokenhub");
        tokenhub.setModel("deepseek/deepseek-flash");
        tokenhub.setModels(List.of("deepseek/deepseek-flash"));

        OpenAiCompatibleProperties.ModelSelection selection =
                properties.resolveModel("tokenhub:deepseek/deepseek-flash");

        assertThat(selection.model()).isEqualTo("deepseek/deepseek-flash");
        assertThat(selection.publicModelId()).isEqualTo("tokenhub:deepseek/deepseek-flash");
        assertThat(selection.route().key()).isEqualTo("tokenhub");
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

    @Test
    void shouldApplyPreferredOrderToSingleTokenHubProviderAndKeepCustomModelsLast() {
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setMultiProviderEnabled(false);
        properties.setProviderName("tokenhub");
        properties.setEndpoint(URI.create("https://tokenhub.example.com/v1/chat/completions"));
        properties.setApiKey("tokenhub-secret");
        properties.setModel("glm-5.3-flashx");
        properties.setModels(List.of("custom-tokenhub-model", "minimax-m3", "hy4-preview"));

        assertThat(properties.getAvailableModelDescriptors())
                .extracting(OpenAiCompatibleProperties.ModelDescriptor::id)
                .containsExactly(
                        "deepseek/deepseek-flash",
                        "deepseek-v4-pro-0813",
                        "kimi-k3",
                        "kimi-k2.8-preview",
                        "kimi-k2.7-code",
                        "glm-5.3",
                        "glm-5.3-flashx",
                        "hy4-preview",
                        "hy3",
                        "minimax-m3",
                        "custom-tokenhub-model"
                );
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
