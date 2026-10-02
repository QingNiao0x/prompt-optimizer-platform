package com.promptoptimizer.provider.infrastructure.concurrency;

import com.promptoptimizer.enhancement.service.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.service.OptimizationPlanningService;
import com.promptoptimizer.history.service.OptimizationHistoryService;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import com.promptoptimizer.identity.support.TestActors;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 经过真实认证主体、MVC 拦截器和生产 RestClient 的受控上游并发验证。
 * 上游绑定随机本机端口，不访问真实模型或消耗账户余额。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootTest(properties = {
        "app.security.local-user.password=concurrency-test-password1",
        "app.provider.mode=openai-compatible",
        "app.provider.openai-compatible.multi-provider-enabled=false",
        "app.provider.openai-compatible.api-key=test-only-key",
        "app.provider.openai-compatible.model=test-model",
        "app.provider.openai-compatible.read-timeout=10s",
        "app.provider.concurrency.store-mode=MEMORY"
})
@ActiveProfiles("local-mock")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ModelConcurrencyHttpIntegrationTest {

    private static final TestUpstream UPSTREAM = new TestUpstream();

    @Autowired private MockMvc mvc;
    @Autowired @Qualifier("openAiCompatibleRestClient") private RestClient modelClient;
    @MockBean private EnhancementOrchestrator enhancement;
    @MockBean private OptimizationPlanningService planning;
    @MockBean private OptimizationHistoryService history;

    @DynamicPropertySource
    static void localModelEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.provider.openai-compatible.endpoint", () -> UPSTREAM.endpoint("/chat/completions"));
    }

    @BeforeEach
    void routeTestOperationsThroughProductionHttpClient() {
        UPSTREAM.reset();
        when(enhancement.optimize(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, com.promptoptimizer.enhancement.dto.OptimizationRequest.class);
            String path = switch (request.rawPrompt()) {
                case "upstream-error" -> "/failure";
                case "invalid-json" -> "/invalid";
                default -> "/chat/completions";
            };
            modelClient.post().uri(UPSTREAM.endpoint(path)).contentType(APPLICATION_JSON)
                    .body(Map.of("model", "test-model")).retrieve().body(Map.class);
            return null;
        });
        when(planning.plan(any())).thenAnswer(invocation -> {
            modelClient.post().uri(UPSTREAM.endpoint("/chat/completions")).contentType(APPLICATION_JSON)
                    .body(Map.of("model", "test-model")).retrieve().body(Map.class);
            return null;
        });
    }

    @AfterEach
    void releaseAnyBlockedResponses() {
        UPSTREAM.release();
        await().atMost(Duration.ofSeconds(5)).until(() -> UPSTREAM.active.get() == 0);
    }

    @AfterAll
    static void stopOwnedMockUpstream() {
        UPSTREAM.close();
    }

    @Test
    void holdsFiftyRealHttpResponsesRejectsFiftyFirstAndThenRecovers() throws Exception {
        UPSTREAM.block(50);
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<MvcResult>> requests = new ArrayList<>();
        try {
            for (int i = 0; i < 50; i++) {
                UUID account = UUID.randomUUID();
                requests.add(callers.submit(() -> mvc.perform(request(account, "/api/v1/optimizations", "generate"))
                        .andReturn()));
            }
            assertThat(UPSTREAM.entered.await(8, TimeUnit.SECONDS)).isTrue();
            assertThat(UPSTREAM.active.get()).isEqualTo(50);
            UUID waitingAccount = UUID.randomUUID();
            // 重复拒绝也必须释放账号名额，不能让平台繁忙变成该账号永久达到三并发。
            for (int i = 0; i < 4; i++) {
                mvc.perform(request(waitingAccount, "/api/v1/optimizations", "generate"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.error.code").value("MODEL_CONCURRENCY_LIMIT"))
                        .andExpect(header().string("Retry-After", "1"));
            }
            assertThat(UPSTREAM.received.get()).isEqualTo(50);
            mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
            UPSTREAM.release();
            for (var result : requests) assertThat(result.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            mvc.perform(request(waitingAccount, "/api/v1/optimizations", "generate")).andExpect(status().isOk());
            assertThat(UPSTREAM.peak.get()).isEqualTo(50);
        } finally {
            UPSTREAM.release();
            callers.close();
        }
    }

    @Test
    void sharesThreeAcrossDifferentSessionsAndModelEndpointsIgnoringSpoofedUserHeader() throws Exception {
        UUID account = UUID.randomUUID();
        UPSTREAM.block(3);
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<MvcResult>> requests = new ArrayList<>();
        try {
            for (String path : List.of("/api/v1/optimizations", "/api/v1/optimizations/plan", "/api/v1/optimizations")) {
                requests.add(callers.submit(() -> mvc.perform(request(account, path, "generate")).andReturn()));
            }
            assertThat(UPSTREAM.entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (String path : List.of("/api/v1/optimizations", "/api/v1/optimizations/plan",
                    "/api/v1/context/analyze", "/api/v1/context/planning")) {
                mvc.perform(request(account, path, "generate").header("X-User-Id", UUID.randomUUID()))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.error.code").value("USER_MODEL_CONCURRENCY_LIMIT"))
                        .andExpect(jsonPath("$.error.retryable").value(true));
            }
            assertThat(UPSTREAM.received.get()).isEqualTo(3);
            Future<MvcResult> otherAccount = callers.submit(() ->
                    mvc.perform(request(UUID.randomUUID(), "/api/v1/optimizations", "generate")).andReturn());
            await().atMost(Duration.ofSeconds(5)).until(() -> UPSTREAM.received.get() == 4);
            UPSTREAM.release();
            for (var result : requests) assertThat(result.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(otherAccount.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            mvc.perform(request(account, "/api/v1/optimizations", "generate")).andExpect(status().isOk());
        } finally {
            UPSTREAM.release();
            callers.close();
        }
    }

    @Test
    void releasesBothLimitsAfterHttpErrorsInvalidResponsesAndValidationFailures() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            mvc.perform(request(account, "/api/v1/optimizations", "upstream-error"))
                    .andExpect(status().is5xxServerError());
            mvc.perform(request(account, "/api/v1/optimizations", "invalid-json"))
                    .andExpect(status().is5xxServerError());
            mvc.perform(request(account, "/api/v1/optimizations", ""))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(request(account, "/api/v1/optimizations", "generate")).andExpect(status().isOk());
    }

    @Test
    void doesNotReplaceAuthenticationOrCsrfWithClientSuppliedIdentity() throws Exception {
        mvc.perform(post("/api/v1/optimizations").with(csrf()).header("X-User-Id", UUID.randomUUID())
                        .contentType(APPLICATION_JSON).content("{\"rawPrompt\":\"generate\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/optimizations").with(user(principal(UUID.randomUUID())))
                        .contentType(APPLICATION_JSON).content("{\"rawPrompt\":\"generate\"}"))
                .andExpect(status().isForbidden());
        assertThat(UPSTREAM.received.get()).isZero();
    }

    /** 每次新建 Session 模拟独立设备；账号身份仍由真实 Spring Security 适配器读取。 */
    private MockHttpServletRequestBuilder request(UUID account, String path, String prompt) {
        return post(path).session(new MockHttpSession()).with(user(principal(account))).with(csrf())
                .contentType(APPLICATION_JSON).content("{\"rawPrompt\":\"" + prompt + "\",\"context\":{\"files\":[]}}");
    }

    private AuthenticatedUser principal(UUID account) {
        return new AuthenticatedUser(account, TestActors.TENANT_ID, UUID.randomUUID(),
                "concurrency@test.local", "Concurrent User", "unused-test-hash", "ACTIVE",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** 先发送响应头，再阻塞正文，用于发现提前释放上游名额的实现错误。 */
    private static final class TestUpstream implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger received = new AtomicInteger();
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch finished = new CountDownLatch(0);

        private TestUpstream() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(workers);
                server.createContext("/", exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    received.incrementAndGet();
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    CountDownLatch completion = finished;
                    try {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/failure") ? 500 : 200, 0);
                        exchange.getResponseBody().write(' ');
                        exchange.getResponseBody().flush();
                        entered.countDown();
                        if (!completion.await(15, TimeUnit.SECONDS)) throw new IOException("test upstream wait expired");
                        String body = exchange.getRequestURI().getPath().equals("/invalid") ? "not-json" : "{\"ok\":true}";
                        exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                        active.decrementAndGet();
                    }
                });
                server.start();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private String endpoint(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        private void reset() {
            entered = new CountDownLatch(0);
            finished = new CountDownLatch(0);
            active.set(0);
            peak.set(0);
            received.set(0);
        }
        private void block(int count) {
            entered = new CountDownLatch(count);
            finished = new CountDownLatch(1);
        }
        private void release() { finished.countDown(); }
        @Override public void close() {
            release();
            server.stop(0);
            workers.close();
        }
    }
}
