package com.promptoptimizer.provider.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleRouteProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证用户可见模型目录只展示官方名称，同时不泄露上游密钥。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AvailableModelControllerTest {

    @Test
    void shouldExposeOfficialNamesWithoutProviderSuffixOrSecrets() {
        OpenAiCompatibleProperties properties = properties();
        AvailableModelController controller = new AvailableModelController(properties);
        HttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "models-request");

        ApiResponse<List<AvailableModel>> response = controller.list(request);

        assertThat(response.requestId()).isEqualTo("models-request");
        assertThat(response.data())
                .extracting(AvailableModel::id)
                .containsExactly(
                        "deepseek:deepseek-flash",
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
        assertThat(response.data())
                .extracting(AvailableModel::displayName)
                .containsExactly(
                        "DeepSeek-V4.1-Flash",
                        "DeepSeek-V4-Pro",
                        "Kimi K3",
                        "Kimi K2.8 Preview",
                        "Kimi K2.7 Code",
                        "GLM-5.3",
                        "GLM-5.3-FlashX",
                        "Hy4 preview",
                        "Hy3",
                        "MiniMax-M3"
                );
        assertThat(response.data())
                .extracting(AvailableModel::defaultModel)
                .containsExactly(false, true, false, false, false, false, false, false, false, false);
        assertThat(response.data())
                .extracting(AvailableModel::displayName)
                .noneMatch(label -> label.contains(" · "));
        assertThat(response.data().toString()).doesNotContain("deepseek-secret", "tokenhub-secret");
    }

    /** 构造保留旧环境变量、但将 V4.1-Flash 公开为 DeepSeek 直连模型的配置。 */
    private OpenAiCompatibleProperties properties() {
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
                "deepseek-v4-pro-0813",
                List.of(
                        "deepseek/deepseek-flash",
                        "hy4-preview",
                        "hy3",
                        "glm-5.3",
                        "kimi-k2.8-preview",
                        "kimi-k2.7-code",
                        "glm-5.3-flashx",
                        "deepseek-v4-pro-0813",
                        "kimi-k3",
                        "minimax-m3"
                )
        );
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setMultiProviderEnabled(true);
        properties.setDefaultProvider("tokenhub");
        properties.setModelCatalogHiddenIds(List.of("deepseek:deepseek-chat"));
        LinkedHashMap<String, OpenAiCompatibleRouteProperties> routes = new LinkedHashMap<>();
        routes.put("deepseek", deepseek);
        routes.put("tokenhub", tokenhub);
        properties.setProviders(routes);
        return properties;
    }

    /** 构造带隔离 endpoint 与密钥的测试路由。 */
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
