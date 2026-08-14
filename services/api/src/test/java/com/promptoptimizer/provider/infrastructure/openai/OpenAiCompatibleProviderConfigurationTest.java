package com.promptoptimizer.provider.infrastructure.openai;

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
}
