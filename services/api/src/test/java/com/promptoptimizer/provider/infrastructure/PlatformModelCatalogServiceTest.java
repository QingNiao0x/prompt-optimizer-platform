package com.promptoptimizer.provider.infrastructure;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import com.promptoptimizer.provider.mapper.PlatformModelMapper;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证用户只能解析启用且具有服务端路由的目录项。 */
class PlatformModelCatalogServiceTest {

    @Test
    void resolvesOnlyEnabledPublishedModelsAndNeverFallsBackToUnpublishedRouteModels() {
        PlatformModelMapper mapper = mock(PlatformModelMapper.class);
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setProviderName("deepseek");
        properties.setEndpoint(URI.create("https://example.invalid/chat/completions"));
        properties.setApiKey("test-only-secret");
        properties.setModel("deepseek-chat");
        properties.setModels(List.of("deepseek-chat", "hidden-model"));
        ModelEntry visible = new ModelEntry(UUID.randomUUID(), "deepseek-chat", "legacy",
                "deepseek-chat", "公开模型", true, true, 0);
        ModelEntry disabled = new ModelEntry(UUID.randomUUID(), "hidden-model", "legacy",
                "hidden-model", "已停用", false, false, 1);
        ModelEntry missingRoute = new ModelEntry(UUID.randomUUID(), "orphan-model", "gone",
                "orphan-model", "无路由", true, false, 2);
        when(mapper.selectAllActive())
                .thenReturn(List.of(visible, disabled, missingRoute));
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformModelMapper> mapperProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiCompatibleProperties> propertiesProvider = mock(ObjectProvider.class);
        when(mapperProvider.getIfAvailable()).thenReturn(mapper);
        when(propertiesProvider.getIfAvailable()).thenReturn(properties);
        PlatformModelCatalogService catalog = new PlatformModelCatalogService(mapperProvider, propertiesProvider);

        assertThat(catalog.available()).containsExactly(visible);
        assertThat(catalog.resolve(null)).isEqualTo(visible);
        assertThatThrownBy(() -> catalog.resolve("hidden-model"))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> catalog.resolve("orphan-model"))
                .isInstanceOf(InvalidOptimizationRequestException.class);
    }
}
