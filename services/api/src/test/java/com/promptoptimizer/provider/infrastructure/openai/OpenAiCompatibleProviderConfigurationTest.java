package com.promptoptimizer.provider.infrastructure.openai;

import com.promptoptimizer.context.application.DocumentSummaryModel;
import com.promptoptimizer.provider.application.PromptEnhancementProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    RestClientAutoConfiguration.class
            ))
            .withUserConfiguration(OpenAiCompatibleProviderConfiguration.class);

    @Test
    void shouldRegisterOpenAiCompatibleProviderWhenModeIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.provider.mode=openai-compatible",
                        "app.provider.openai-compatible.api-key=test-key",
                        "app.provider.openai-compatible.model=test-model"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PromptEnhancementProvider.class);
                    assertThat(context).hasSingleBean(DocumentSummaryModel.class);
                    assertThat(context.getBean(PromptEnhancementProvider.class))
                            .isInstanceOf(OpenAiCompatiblePromptEnhancementProvider.class);
                });
    }

    @Test
    void shouldRejectUnsupportedEndpointScheme() {
        contextRunner
                .withPropertyValues(
                        "app.provider.mode=openai-compatible",
                        "app.provider.openai-compatible.api-key=test-key",
                        "app.provider.openai-compatible.model=test-model",
                        "app.provider.openai-compatible.endpoint=file:///tmp/model"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("http 或 https");
                });
    }

    @Test
    void shouldBindDeepSeekAndTokenHubRoutesTogether() {
        contextRunner
                .withPropertyValues(
                        "app.provider.mode=openai-compatible",
                        "app.provider.openai-compatible.multi-provider-enabled=true",
                        "app.provider.openai-compatible.default-provider=tokenhub",
                        "app.provider.openai-compatible.providers.deepseek.provider-name=deepseek",
                        "app.provider.openai-compatible.providers.deepseek.endpoint=https://deepseek.example.com/chat/completions",
                        "app.provider.openai-compatible.providers.deepseek.api-key=deepseek-secret",
                        "app.provider.openai-compatible.providers.deepseek.model=deepseek-chat",
                        "app.provider.openai-compatible.providers.deepseek.models=deepseek-chat",
                        "app.provider.openai-compatible.providers.tokenhub.provider-name=tokenhub",
                        "app.provider.openai-compatible.providers.tokenhub.endpoint=https://tokenhub.example.com/v1/chat/completions",
                        "app.provider.openai-compatible.providers.tokenhub.api-key=tokenhub-secret",
                        "app.provider.openai-compatible.providers.tokenhub.model=glm-5.3-flashx",
                        "app.provider.openai-compatible.providers.tokenhub.models=glm-5.3-flashx,kimi-k3"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PromptEnhancementProvider.class);
                    OpenAiCompatibleProperties properties = context.getBean(OpenAiCompatibleProperties.class);
                    assertThat(properties.getAvailableModelDescriptors())
                            .extracting(OpenAiCompatibleProperties.ModelDescriptor::id)
                            .containsExactly("deepseek:deepseek-chat", "tokenhub:glm-5.3-flashx", "tokenhub:kimi-k3");
                    assertThat(properties.getDefaultRoute().providerName()).isEqualTo("tokenhub");
                });
    }
}
