package com.promptoptimizer.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.infrastructure.ClientIpResolver;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

/** 真实试用业务与内存适配器的额度、并发及重放测试，不创建账号或调用付费模型。 */
class TrialServiceTest {
    private TrialService service;
    private TrialStore store;
    private ObjectMapper json;
    @BeforeEach void setup() {
        json = new ObjectMapper().findAndRegisterModules();
        store = new TrialStore(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class), json, "MEMORY", "mock");
        service = new TrialService(store, json, new ClientIpResolver(""), true, true);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private MockHttpServletRequest guest(String ip) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        service.start(request, response);
        String cookie = response.getHeader("Set-Cookie");
        request.setCookies(new Cookie(TrialService.COOKIE, cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'))));
        return request;
    }
    @Test void readOnlyStatusDoesNotCreateIdentityAndCookieIsProtected() {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.1");
        assertThat(service.status(request).initialized()).isFalse();
        var response = new MockHttpServletResponse();
        assertThat(service.start(request, response).initialized()).isTrue();
        assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly", "Secure", "SameSite=Lax");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void eachFeatureHasIndependentLifetimeLimitAndRepeatedSessionDoesNotReset() {
        var request = guest("192.0.2.1");
        for (var feature : TrialFeature.values()) {
            for (int count = 0; count < feature.limit(); count++) {
                assertThat(service.execute(request, feature, UUID.randomUUID(), "input", String.class, () -> "result")).isEqualTo("result");
            }
            assertThatThrownBy(() -> service.execute(request, feature, UUID.randomUUID(), "input", String.class, () -> "never"))
                    .isInstanceOfSatisfying(TrialException.class, e -> assertThat(e.code()).isEqualTo("TRIAL_QUOTA_EXHAUSTED"));
        }
        var response = new MockHttpServletResponse();
        assertThat(service.start(request, response).quotas().values()).allMatch(q -> q.remaining() == 0);
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }
    @Test void replayReturnsSameResultButChangedPayloadOrOtherGuestCannotRecoverIt() {
        var first = guest("192.0.2.1"); var second = guest("192.0.2.2"); var id = UUID.randomUUID();
        var calls = new AtomicInteger();
        service.execute(first, TrialFeature.TEXT_POLISH, id, "original", String.class, () -> "result" + calls.incrementAndGet());
        assertThat(service.execute(first, TrialFeature.TEXT_POLISH, id, "original", String.class, () -> "forbidden")).isEqualTo("result1");
        assertThat(calls).hasValue(1);
        assertThat(service.status(first).quotas().get("textPolish").used()).isEqualTo(1);
        assertThatThrownBy(() -> service.execute(first, TrialFeature.TEXT_POLISH, id, "changed", String.class, () -> "forbidden"))
                .isInstanceOfSatisfying(TrialException.class, e -> assertThat(e.code()).isEqualTo("TRIAL_OPERATION_CONFLICT"));
        assertThatThrownBy(() -> service.operation(second, TrialFeature.TEXT_POLISH, id, String.class)).isInstanceOf(TrialException.class);
    }
    @Test void downstreamFailureConsumesOneAttemptAndCannotBeReplayed() {
        var request = guest("192.0.2.1"); var id = UUID.randomUUID();
        assertThatThrownBy(() -> service.execute(request, TrialFeature.ENHANCEMENT, id, "input", String.class,
                () -> { throw new IllegalStateException("synthetic failure"); })).isInstanceOf(IllegalStateException.class);
        assertThat(service.status(request).quotas().get("enhancement").used()).isEqualTo(1);
        assertThat(service.operation(request, TrialFeature.ENHANCEMENT, id, String.class).status()).isEqualTo("FAILED");
        assertThatThrownBy(() -> service.execute(request, TrialFeature.ENHANCEMENT, id, "input", String.class, () -> "forbidden"))
                .isInstanceOf(TrialException.class);
    }
    @Test void concurrentSubmissionOnlyAllowsOneModelExecution() throws Exception {
        var request = guest("192.0.2.1"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var running = pool.submit(() -> service.execute(request, TrialFeature.IMAGE_PROMPT, UUID.randomUUID(), "input", String.class, () -> {
                entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                return "result";
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.execute(request, TrialFeature.ENHANCEMENT, UUID.randomUUID(), "input", String.class, () -> "forbidden"))
                    .isInstanceOfSatisfying(TrialException.class, e -> assertThat(e.code()).isEqualTo("TRIAL_REQUEST_RUNNING"));
            release.countDown(); assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo("result");
        }
    }
    @Test void nonexistentOrForgedCookieIsRejectedAndRedisNeverFallsBack() {
        var request = new MockHttpServletRequest(); request.setCookies(new Cookie(TrialService.COOKIE, "a".repeat(64)));
        assertThatThrownBy(() -> service.guest(request)).isInstanceOf(TrialException.class);
        var missing = new TrialStore(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class), json, "REDIS", "openai-compatible");
        assertThatThrownBy(() -> missing.exists("guest")).isInstanceOfSatisfying(TrialException.class, e -> assertThat(e.status()).isEqualTo(503));
        assertThatThrownBy(() -> new TrialStore(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class), json, "MEMORY", "openai-compatible"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void clearingCookiesCannotBypassSourceCreationBudget() {
        for (int count = 0; count < 10; count++) guest("192.0.2.1");
        assertThatThrownBy(() -> guest("192.0.2.1")).isInstanceOfSatisfying(TrialException.class, e -> assertThat(e.code()).isEqualTo("TRIAL_RATE_LIMITED"));
    }
    @Test void repeatedUploadAndRevokeCannotCreateUnlimitedDecodeWork() {
        var request = guest("192.0.2.1"); String guest = service.guest(request);
        for (int i = 0; i < 5; i++) service.claimUpload(guest);
        assertThatThrownBy(() -> service.claimUpload(guest)).isInstanceOfSatisfying(TrialException.class,
                e -> assertThat(e.code()).isEqualTo("TRIAL_UPLOAD_LIMIT"));
        assertThat(service.status(request).quotas().get("imagePrompt").remaining()).isEqualTo(1);
    }

    @Test void trialReachesActualModelHttpAdapterWithoutFormalActorAndReplaysOnlyOnce() throws Exception {
        var request = guest("192.0.2.1");
        var builder = org.springframework.web.client.RestClient.builder();
        var transport = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var properties = new com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties();
        properties.setEndpoint(java.net.URI.create("https://fixture.invalid/chat/completions"));
        properties.setApiKey("fixture-key"); properties.setModel("fixture");
        var model = new com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry(UUID.randomUUID(), "fixture",
                properties.getConfiguredRoutes().getFirst().key(), "fixture", "合成测试模型", true, true, 0);
        var models = org.mockito.Mockito.mock(com.promptoptimizer.textpolish.service.TextPolishModelService.class);
        org.mockito.Mockito.when(models.require(org.mockito.ArgumentMatchers.any())).thenReturn(model);
        var fidelity = new com.promptoptimizer.textpolish.service.TextPolishFidelity();
        var provider = new com.promptoptimizer.textpolish.service.OpenAiTextPolishProvider(builder.build(), json, properties, fidelity);
        var current = org.mockito.Mockito.mock(com.promptoptimizer.identity.service.CurrentActor.class);
        var history = org.mockito.Mockito.mock(com.promptoptimizer.textpolish.service.TextPolishHistoryService.class);
        var actual = new com.promptoptimizer.textpolish.service.TextPolishService(current, models, provider, history,
                org.mockito.Mockito.mock(com.promptoptimizer.textpolish.service.TextPolishStateStore.class), fidelity, json);
        var input = new com.promptoptimizer.textpolish.domain.TextPolishModels.Generate(UUID.randomUUID(),
                new com.promptoptimizer.textpolish.domain.TextPolishModels.Input("合成测试原文。", null, null, "", "", List.of(), "fixture"), null, null, null);
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "{\"polishedText\":\"合成测试原文。\"}")))));
        transport.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://fixture.invalid/chat/completions"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(envelope, org.springframework.http.MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            var result = service.execute(request, TrialFeature.TEXT_POLISH, input.requestId(), input,
                    com.promptoptimizer.textpolish.domain.TextPolishModels.Document.class, () -> actual.generateTrial(input));
            assertThat(result.polishedText()).isEqualTo("合成测试原文。"); assertThat(result.historySaved()).isFalse();
        }
        transport.verify();
        org.mockito.Mockito.verifyNoInteractions(current, history);
        assertThat(service.status(request).quotas().get("textPolish").used()).isEqualTo(1);
    }
}
