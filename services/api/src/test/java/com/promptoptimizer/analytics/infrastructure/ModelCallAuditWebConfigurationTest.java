package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.service.impl.ModelCallAuditService;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证真实 MVC 拦截器采集受信任租户并在请求结束后清除线程范围。 */
@WebMvcTest(ModelCallAuditWebConfigurationTest.CallController.class)
@Import({ModelCallAuditWebConfiguration.class, RequestIdFilter.class, AuthenticatedMvcTestConfiguration.class,
        ModelCallAuditWebConfigurationTest.CallController.class})
class ModelCallAuditWebConfigurationTest {
    @Autowired private MockMvc mvc;
    @MockBean private CurrentActor actor;
    @MockBean private ModelCallAuditService audit;

    @Test
    void callerCannotOverrideTenantAndRequestScopeIsRestored() throws Exception {
        UUID tenant = UUID.randomUUID();
        when(actor.require()).thenReturn(new ActorIdentity(UUID.randomUUID(), tenant, UUID.randomUUID(), "fixture@example.test", "fixture"));
        String request = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/optimizations/ledger-test").header("X-Request-Id", request)
                        .param("tenantId", UUID.randomUUID().toString()).with(user("fixture").roles("USER")).with(csrf()))
                .andExpect(status().isOk());
        var fact = ArgumentCaptor.forClass(ModelCallObservation.class);
        verify(audit).record(eq(tenant), fact.capture());
        assertThat(fact.getValue().requestId()).isEqualTo(request);
        assertThat(fact.getValue().usage().totalTokens()).isEqualTo(3);
        assertThat(ModelCallAuditContext.capture()).isNull();
    }

    /** 测试控制器仅输出固定响应，模拟 Provider 完成日志的真实采集入口。 */
    @RestController
    public static class CallController {
        @PostMapping("/api/v1/optimizations/ledger-test")
        public String call() {
            try (var attempt = ModelCallLogger.beginAttempt()) {
                ModelCallLogger.completed("prompt.optimize", "fixture-provider", "fixture-model", "PLATFORM_DEFAULT",
                        false, 1, 1, 1, new ModelCallLogger.TokenUsage(1L, 2L, 3L));
            }
            return "done";
        }
    }
}
