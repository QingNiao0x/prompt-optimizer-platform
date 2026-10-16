package com.promptoptimizer.provider.infrastructure.openai;

import com.promptoptimizer.common.exception.GlobalExceptionHandler;
import com.promptoptimizer.provider.domain.ProviderCapacityException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderCapacityErrorTest {
    @Test
    void capacityIsRecoverableWithoutAnUpstream502() {
        var result = new GlobalExceptionHandler().handleProviderException(new ProviderCapacityException(), new MockHttpServletRequest());
        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getBody().error().code()).isEqualTo("MODEL_CAPACITY_EXCEEDED");
        assertThat(result.getBody().error().retryable()).isFalse();
        assertThat(result.getBody().error().details()).containsEntry("upstreamCalled", false);
    }

    @Test
    void checkedInCapacitySnapshotBindsByCompletePublicRouteId() throws Exception {
        var environment = new StandardEnvironment();
        var sources = new YamlPropertySourceLoader().load("capacity", new ClassPathResource("application.yml"));
        sources.forEach(source -> environment.getPropertySources().addLast(source));
        var limits = Binder.get(environment).bind("app.provider.openai-compatible.capacity-limits",
                Bindable.mapOf(String.class, RequestCapacityPolicy.Limit.class)).get();
        assertThat(limits).containsKeys("deepseek:deepseek-flash", "deepseek:deepseek-v4-pro");
        assertThat(limits.get("deepseek:deepseek-flash").contextTokens()).isEqualTo(1_000_000);
    }
}
