package com.promptoptimizer.provider.infrastructure.concurrency;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.infrastructure.AuditEventDelivery;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import com.promptoptimizer.identity.security.BootstrapAdminAccountInitializer;
import com.promptoptimizer.identity.security.BootstrapUserPasswordInitializer;
import com.promptoptimizer.identity.service.LoginCaptchaService;
import com.promptoptimizer.identity.service.impl.LoginCaptchaServiceImpl;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import jakarta.servlet.http.HttpServletRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 显式授权后执行的付费模型并发验收：真实 HTTP、认证、Redis、PostgreSQL 提交和模型响应。
 * 使用随机独立数据库 schema、会话空间与审计目录；不修改既有服务或记录凭据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@EnabledIfSystemProperty(named = "realModelConcurrency.enabled", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.flyway.enabled=true", "spring.flyway.clean-disabled=false",
        "app.provider.mode=openai-compatible", "app.provider.openai-compatible.default-provider=deepseek",
        "app.provider.concurrency.store-mode=REDIS", "app.history.enabled=true",
        "app.security.registration.enabled=false", "decorator.datasource.enabled=false",
        "app.analytics.geoip.database=", "logging.level.com.promptoptimizer=INFO"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RealModelConcurrencyAcceptanceTest {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "model_load_" + RUN;
    private static final String PREFIX = "prompt-optimizer:test:real-model:" + RUN;
    private static final Path OUTPUT = Path.of("target", "real-model-concurrency", RUN).toAbsolutePath();
    private static final List<String> MODELS = List.of("deepseek:deepseek-flash", "deepseek:deepseek-v4-pro");
    private static final int CAPACITY = 100;
    private static final String DESCRIPTION = "这是公开虚构场景；上传资料均为测试生成，不包含真实用户或开发者项目资料。";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private IdentityProvisioningMapper provisioning;
    @Autowired private PasswordEncoder encoder;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ModelConcurrencyProperties limits;
    @Autowired private OpenAiCompatibleProperties provider;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private AuditEventDelivery delivery;
    @SpyBean private LoginCaptchaServiceImpl captcha;
    @MockBean private BootstrapAdminAccountInitializer adminInitializer;
    @MockBean private BootstrapUserPasswordInitializer userInitializer;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Session> sessions = new ArrayList<>();
    private final List<Map<String, Object>> stages = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final AtomicReference<String> captchaAnswer = new AtomicReference<>();
    private final ModelEvents modelEvents = new ModelEvents();
    private JsonNode fixtures;

    /** 只允许本机 PostgreSQL；随机 schema 不包含 public，也不继承开发服务的会话和审计目录。 */
    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry properties) {
        String base = System.getProperty("realModelConcurrency.databaseUrl",
                "jdbc:postgresql://localhost:5432/prompt_optimizer");
        URI location = URI.create(base.substring("jdbc:".length()));
        if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(location.getHost()) || location.getQuery() != null) {
            throw new IllegalArgumentException("付费验收仅允许无查询参数的本机测试数据库地址");
        }
        properties.add("spring.datasource.url", () -> base + "?currentSchema=" + SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.session.redis.namespace", () -> PREFIX + ":session");
        properties.add("app.provider.concurrency.key-prefix", () -> PREFIX);
        properties.add("app.analytics.delivery.journal-directory", () -> OUTPUT.resolve("journal").toString());
    }

    @Test
    void measuresRealProviderAndPlatformCapacity() throws Exception {
        Files.createDirectories(OUTPUT);
        report.put("runId", RUN);
        report.put("startedAt", Instant.now().toString());
        report.put("stage", System.getProperty("realModelConcurrency.stage", "smoke"));
        report.put("models", MODELS);
        report.put("databaseSchema", SCHEMA);
        report.put("stages", stages);
        report.put("passed", false);
        report.put("browserDeadlineMs", 70_000);
        report.put("transportDeadlineMs", 240_000);
        report.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("globalLimit", CAPACITY);
        report.put("userLimit", 3);
        Logger modelLogger = (Logger) LoggerFactory.getLogger("com.promptoptimizer.common.logging.ModelCallLogger");
        modelEvents.start();
        modelLogger.addAppender(modelEvents);
        try {
            assertThat(limits.getGlobalLimit()).isEqualTo(CAPACITY);
            assertThat(limits.getUserLimit()).isEqualTo(3);
            assertThat(limits.getStoreMode()).isEqualTo(ModelConcurrencyProperties.StoreMode.REDIS);
            for (String model : MODELS) {
                var route = provider.resolveModel(model).route();
                assertThat(route.endpoint().getHost()).isEqualTo("api.deepseek.com");
                assertThat(route.apiKey().isBlank()).as("已配置目标路由凭据").isFalse();
            }
            report.put("upstreamHost", "api.deepseek.com");
            report.put("readTimeoutMs", provider.getReadTimeout().toMillis());
            report.put("maxOutputTokens", provider.getMaxTokens());
            fixtures = json.readTree(Files.readString(Path.of("target/real-model-fixtures/contexts.json")));
            assertThat(fixtures.path("syntheticOnly").asBoolean()).isTrue();
            report.put("projectIndexEvidence", fixtures.path("indexEvidence"));
            report.put("documentCharacters", fixtures.path("document").path("content").asText().length());
            try (var connection = dataSource.getConnection()) {
                assertThat(connection.getSchema()).isEqualTo(SCHEMA);
                report.put("databaseVersion", connection.getMetaData().getDatabaseProductVersion());
            }
            // 只读取本轮生成的验证码供正常登录；真实生成、校验、密码验证及 CSRF 均执行生产代码。
            doAnswer(call -> {
                Object result = call.callRealMethod();
                HttpServletRequest request = call.getArgument(0);
                captchaAnswer.set((String) request.getSession(false).getAttribute(LoginCaptchaService.ATTRIBUTE));
                return result;
            }).when(captcha).issue(any(HttpServletRequest.class));
            String password = UUID.randomUUID() + "Aa!";
            String hash = encoder.encode(password);
            Account first = account(hash);
            sessions.add(login(first, password));
            for (int i = 1; i < 4; i++) sessions.add(login(account(hash), password));
            assertStatus(call(sessions.getFirst(), "GET", "/api/v1/models", null), 200);
            boolean load = "load".equals(System.getProperty("realModelConcurrency.stage", "smoke"));
            if (!load) {
                prepareContexts(sessions.subList(0, 4), 0);
                for (String model : MODELS) wave("baseline-contexts", model, sessions.subList(0, 4), false);
                wave("baseline-plan", "mixed", sessions.subList(0, 4), false, Operation.PLAN);
                List<Session> ready = sessions.stream().filter(s -> s.plan != null).toList();
                if (!ready.isEmpty()) wave("baseline-confirmed", "mixed", ready, false, Operation.CONFIRMED);
            }
            if (load) {
                // 先单独运行 smoke 核实环境，再显式运行 load；失败结果不会被后续成功覆盖。
                // 每个并发请求来自独立登录会话，账号数量足以避免把用户上限误测成平台容量。
                for (int i = 4; i < CAPACITY + 1; i++) sessions.add(login(account(hash), password));
                prepareContexts(sessions.subList(0, CAPACITY), 0);
                prepareContexts(sessions.subList(CAPACITY, CAPACITY + 1), CAPACITY);
                Reply forbidden = call(sessions.get(1), "GET", "/api/v1/context/documents/" + sessions.getFirst().documentId, null);
                boolean isolated = forbidden.status() == 403 || forbidden.status() == 404;
                stages.add(Map.of("name", "document-owner-isolation", "status", forbidden.status(), "passed", isolated));
                assertThat(isolated).isTrue();
                List<Session> sameAccount = new ArrayList<>();
                sameAccount.add(sessions.getFirst());
                for (int i = 0; i < 3; i++) {
                    Session session = login(first, password);
                    session.copyContext(sessions.getFirst());
                    sessions.add(session);
                    sameAccount.add(session);
                }
                accountBoundary(sameAccount);
                for (String model : MODELS) {
                    var full = wave("concurrency-100-contexts", model, sessions.subList(0, CAPACITY), true);
                    wave("recovery", model, List.of(sessions.getFirst()), false);
                    assertThat((long) full.get("successes")).as("明显故障时停止后续付费扩量").isGreaterThanOrEqualTo(90);
                }
                wave("concurrency-100-plan", "mixed", sessions.subList(0, CAPACITY), false, Operation.PLAN);
                // 只对首次未获得计划的账号补试一次以测完整确认波次；原失败阶段仍使总验收失败。
                List<Session> missing = sessions.subList(0, CAPACITY).stream().filter(s -> s.plan == null).toList();
                if (!missing.isEmpty() && missing.size() <= 10) {
                    wave("plan-explicit-retry-failed-accounts", "mixed", missing, false, Operation.PLAN);
                }
                List<Session> confirmed = sessions.subList(0, CAPACITY).stream().filter(s -> s.plan != null).toList();
                if (!confirmed.isEmpty()) wave("concurrency-100-confirmed", "mixed", confirmed, false, Operation.CONFIRMED);
            }
            report.put("passed", stages.stream().allMatch(stage -> Boolean.TRUE.equals(stage.get("passed"))));
        } catch (Exception | AssertionError failure) {
            report.put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            report.put("modelEvents", List.copyOf(modelEvents.events));
            // 等待在途请求结束再清理，避免把客户端超时误当服务端已停止调用。
            boolean drained = awaitCount(PREFIX + ":global", 0, Duration.ofSeconds(180));
            report.put("allUpstreamPermitsReleased", drained);
            for (Session session : sessions) {
                try {
                    if (session.ownsDocument && !session.documentId.isEmpty()) {
                        assertStatus(call(session, "DELETE", "/api/v1/context/documents/" + session.documentId, null), 200);
                    }
                    call(session, "POST", "/api/v1/auth/logout", Map.of());
                }
                catch (Exception ignored) { report.put("logoutIncomplete", true); }
            }
            delivery.close();
            // Flyway 仅清理本次自行创建的随机测试 schema，不删除既有业务表或用户记录。
            if (drained && Set.of(flyway.getConfiguration().getSchemas()).equals(Set.of(SCHEMA))
                    && SCHEMA.matches("model_load_[a-f0-9]{32}")) {
                flyway.clean();
                report.put("isolatedSchemaRemoved", true);
            }
            modelLogger.detachAppender(modelEvents);
            modelEvents.stop();
            saveReport();
            client.close();
        }
        assertThat(report.get("passed")).as("真实容量结果见脱敏报告").isEqualTo(true);
    }

    /** 使用生产 Mapper 正常提交独立账号数据，HTTP 线程通过真实连接池读写。 */
    private Account account(String hash) {
        Account account = new Account(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID() + "@model-load.invalid");
        provisioning.insertTenant(account.tenantId(), "model-load");
        provisioning.insertUserAccount(account.userId(), account.tenantId(), account.email(), "model-load", hash, "USER");
        provisioning.insertWorkspace(account.workspaceId(), account.tenantId(), "model-load", "", account.userId());
        provisioning.insertWorkspaceMember(account.workspaceId(), account.userId(), "OWNER");
        provisioning.insertUserIdentity(UUID.randomUUID(), account.userId(), "EMAIL", "local", account.email(),
                account.email(), "ACTIVE", OffsetDateTime.now());
        return account;
    }

    /** 登录会话和凭据只存于内存，不写入报告、命令行或日志。 */
    private Session login(Account account, String password) throws Exception {
        Session session = new Session(account.userId());
        refreshCsrf(session);
        assertStatus(call(session, "GET", "/api/v1/auth/captcha", null), 200);
        assertStatus(call(session, "POST", "/api/v1/auth/login", Map.of("identifier", account.email(),
                "password", password, "captcha", captchaAnswer.get())), 200);
        refreshCsrf(session);
        assertStatus(call(session, "GET", "/api/v1/auth/me", null), 200);
        return session;
    }

    private void refreshCsrf(Session session) throws Exception {
        Reply response = call(session, "GET", "/api/v1/auth/csrf", null);
        assertStatus(response, 200);
        session.csrf = response.body().path("data").path("token").asText();
        assertThat(session.csrf.isBlank()).isFalse();
    }

    /** 并发执行真实文档上传、异步解析与上下文分析；项目片段来自生产前端索引器。 */
    private void prepareContexts(List<Session> callers, int offset) throws Exception {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("name", "prepare-upload-and-project-contexts");
        stage.put("requestedConcurrency", callers.size());
        stages.add(stage);
        List<Map<String, Object>> results = new ArrayList<>();
        long started = System.nanoTime();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Map<String, Object>>> tasks = new ArrayList<>();
            for (int i = 0; i < callers.size(); i++) {
                Session session = callers.get(i);
                session.sample = offset + i;
                tasks.add(workers.submit(() -> {
                    gate.await();
                    return prepareContext(session);
                }));
            }
            gate.countDown();
            for (var task : tasks) results.add(task.get(180, TimeUnit.SECONDS));
        }
        stage.put("requests", results);
        stage.put("wallMs", (System.nanoTime() - started) / 1_000_000L);
        stage.put("passed", results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed"))));
        saveReport();
        assertThat(stage.get("passed")).as("上传解析及上下文准备全部完成后才调用付费模型").isEqualTo(true);
    }

    /** 文档正文通过二进制上传接口进入索引，增强请求只携带 documentId，不内嵌伪造摘要。 */
    private Map<String, Object> prepareContext(Session session) throws Exception {
        long started = System.nanoTime();
        boolean document = session.sample % 2 == 0;
        JsonNode fixture = fixtures.path(document ? "document" : "project");
        session.rawPrompt = fixture.path("rawPrompt").asText() + "\n本次检查重点："
                + List.of("正常流程", "异常恢复", "重复操作", "权限边界", "交接验收").get(session.sample % 5)
                + "；场景编号 " + session.sample + "。";
        session.marker = fixture.path("marker").asText();
        Map<String, Object> result = new LinkedHashMap<>();
        if (document) {
            boolean docx = session.sample % 4 == 0;
            session.kind = docx ? "uploaded-docx" : "uploaded-markdown";
            byte[] bytes = fixture.path("content").asText().getBytes(StandardCharsets.UTF_8);
            if (docx) {
                try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    for (String paragraph : fixture.path("content").asText().split("\\n")) {
                        word.createParagraph().createRun().setText(paragraph);
                    }
                    word.write(output);
                    bytes = output.toByteArray();
                }
            }
            String path = "activity-" + session.sample + (docx ? ".docx" : ".md");
            String language = docx ? "docx" : "markdown";
            Reply created = call(session, "POST", "/api/v1/context/documents",
                    Map.of("path", path, "language", language, "sizeBytes", bytes.length));
            assertStatus(created, 201);
            session.documentId = created.body().path("data").path("documentId").asText();
            session.ownsDocument = true;
            String endpoint = "/api/v1/context/documents/" + session.documentId;
            int chunkSize = created.body().path("data").path("chunkSizeBytes").asInt();
            assertThat(chunkSize).isPositive();
            for (int offset = 0, chunk = 0; offset < bytes.length; offset += chunkSize, chunk++) {
                byte[] part = java.util.Arrays.copyOfRange(bytes, offset, Math.min(offset + chunkSize, bytes.length));
                assertStatus(call(session, "PUT", endpoint + "/chunks/" + chunk, part), 200);
            }
            assertStatus(call(session, "POST", endpoint + "/complete", Map.of()), 200);
            JsonNode status;
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            do {
                Reply progress = call(session, "GET", endpoint, null);
                assertStatus(progress, 200);
                status = progress.body().path("data");
                if (Set.of("READY", "PARTIAL", "FAILED").contains(status.path("phase").asText())) break;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            assertThat(status.path("phase").asText()).isEqualTo("READY");
            result.put("uploadedBytes", bytes.length);
            result.put("extractedCharacters", status.path("extractedCharacters").asInt());
            result.put("documentChunks", status.path("chunkCount").asInt());
            session.context = Map.of("customDescription", DESCRIPTION, "files", List.of(
                    Map.of("path", path, "language", language, "content", "", "documentId", session.documentId,
                            "sizeBytes", bytes.length)));
        } else {
            session.kind = "indexed-project";
            session.context = Map.of("customDescription", DESCRIPTION, "files", fixture.path("files"));
        }
        Reply prepared = call(session, "POST", "/api/v1/context/planning",
                Map.of("rawPrompt", session.rawPrompt, "context", session.context));
        assertStatus(prepared, 200);
        JsonNode data = prepared.body().path("data");
        session.contextReference = json.valueToTree(Map.of("contextId", data.path("contextId").asText(),
                "version", data.path("version").asText()));
        result.put("sample", session.sample);
        result.put("contextKind", session.kind);
        result.put("rawPromptCharacters", session.rawPrompt.length());
        result.put("analyzedFiles", data.path("contextReport").path("fileSnippets").size());
        result.put("passed", !data.path("contextId").asText().isBlank()
                && data.path("contextReport").path("fileSnippets").toString().contains(session.marker));
        result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000L);
        return result;
    }

    /** 真实 HTTP 输入输出；只返回业务结果，认证材料不进入统计对象。 */
    private Reply call(Session session, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(240)).header("X-Request-Id", UUID.randomUUID().toString());
        if (session != null) {
            String cookie = session.cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining("; "));
            if (!cookie.isEmpty()) request.header("Cookie", cookie);
            if (!session.csrf.isEmpty()) request.header("X-XSRF-TOKEN", session.csrf);
        }
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else if (body instanceof byte[] bytes) request.header("Content-Type", "application/octet-stream")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        long start = System.nanoTime();
        HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (session != null) for (String header : response.headers().allValues("Set-Cookie")) {
            for (HttpCookie cookie : HttpCookie.parse(header)) session.cookies.put(cookie.getName(), cookie.getValue());
        }
        JsonNode parsed = response.headers().firstValue("Content-Type").orElse("").contains("json")
                ? json.readTree(response.body()) : json.createObjectNode();
        return new Reply(response.statusCode(), parsed, (System.nanoTime() - start) / 1_000_000L);
    }

    /** 同时起跑，持续采样 Redis 在途数量和真实连接池；完成后核对每个账号的历史新增。 */
    private Map<String, Object> wave(String name, String model, List<Session> callers, boolean overflow) throws Exception {
        return wave(name, model, callers, overflow, Operation.DIRECT);
    }

    /** 各阶段独立计时；Plan 返回后用真实 planId 和上下文版本确认，不伪造计划。 */
    private Map<String, Object> wave(String name, String model, List<Session> callers, boolean overflow,
                                     Operation operation) throws Exception {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("name", name);
        stage.put("model", model);
        stage.put("requestedConcurrency", callers.size());
        stage.put("distinctAccounts", callers.stream().map(s -> s.userId).distinct().count());
        stage.put("operation", operation.name());
        stage.put("documentUsers", callers.stream().filter(s -> !s.documentId.isEmpty()).count());
        stage.put("projectUsers", callers.stream().filter(s -> s.documentId.isEmpty()).count());
        stages.add(stage);
        List<Long> before = new ArrayList<>();
        for (Session session : callers) before.add(historyCount(session));
        var monitor = new Monitor();
        List<Map<String, Object>> results = new ArrayList<>();
        long start = System.nanoTime();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); monitor) {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Map<String, Object>>> tasks = new ArrayList<>();
            for (int i = 0; i < callers.size(); i++) {
                int sample = i;
                Session session = callers.get(i);
                tasks.add(workers.submit(() -> {
                    gate.await();
                    return invoke(session, selectedModel(model, session), sample, operation);
                }));
            }
            gate.countDown();
            if (overflow) {
                boolean full = awaitCount(PREFIX + ":global", CAPACITY, Duration.ofSeconds(20));
                stage.put("observedFullCapacity", full);
                if (full) {
                    Reply probe = call(sessions.get(CAPACITY), "POST", "/api/v1/optimizations",
                            payload(sessions.get(CAPACITY), model));
                    stage.put("overflowStatus", probe.status());
                    stage.put("overflowCode", probe.body().path("error").path("code").asText());
                    Reply health = call(null, "GET", "/api/v1/health", null);
                    stage.put("healthStatusAtCapacity", health.status());
                    stage.put("healthLatencyMsAtCapacity", health.elapsedMs());
                }
            }
            for (var task : tasks) results.add(task.get(260, TimeUnit.SECONDS));
        }
        stage.put("wallMs", (System.nanoTime() - start) / 1_000_000L);
        stage.putAll(monitor.snapshot());
        stage.put("requests", results);
        long successes = results.stream().filter(r -> Boolean.TRUE.equals(r.get("valid"))).count();
        stage.put("successes", successes);
        stage.put("allSucceeded", successes == callers.size());
        List<Long> times = results.stream().map(r -> (long) r.get("elapsedMs")).sorted().toList();
        stage.put("p50Ms", percentile(times, .50));
        stage.put("p95Ms", percentile(times, .95));
        stage.put("maxMs", times.getLast());
        stage.put("withinBrowserDeadline", times.stream().allMatch(t -> t < 70_000));
        stage.put("beyondBrowserDeadline", times.stream().filter(t -> t >= 70_000).count());
        int historySaved = 0;
        int historyCorrect = 0;
        for (int i = 0; i < callers.size(); i++) {
            long added = historyCount(callers.get(i)) - before.get(i);
            boolean successful = Boolean.TRUE.equals(results.get(i).get("httpSucceeded"));
            long expected = operation == Operation.PLAN || !successful ? 0 : 1;
            if (added == expected) historyCorrect++;
            if (expected == 1 && added == 1) historySaved++;
        }
        stage.put("historyRecordsVerified", historySaved);
        stage.put("historyAccountsCorrect", historyCorrect);
        boolean boundary = !overflow || Boolean.TRUE.equals(stage.get("observedFullCapacity"))
                && Integer.valueOf(503).equals(stage.get("overflowStatus"))
                && "MODEL_CONCURRENCY_LIMIT".equals(stage.get("overflowCode"));
        stage.put("passed", successes == callers.size() && historyCorrect == callers.size() && boundary
                && monitor.upstreamPeak <= CAPACITY
                && Boolean.TRUE.equals(stage.get("withinBrowserDeadline")));
        saveReport();
        return stage;
    }

    /** 同账号四个独立真实登录 Session，前三个执行期间第 4 个必须由服务端拒绝。 */
    private void accountBoundary(List<Session> sameAccount) throws Exception {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("name", "account-3-across-sessions");
        stages.add(stage);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Object>>> running = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int sample = i;
                running.add(workers.submit(() -> enhance(sameAccount.get(sample), MODELS.getFirst(), sample)));
            }
            boolean full = awaitCount(PREFIX + ":user:" + sameAccount.getFirst().userId, 3, Duration.ofSeconds(10));
            stage.put("observedThree", full);
            Reply fourth = call(sameAccount.get(3), "POST", "/api/v1/optimizations", payload(sameAccount.get(3), MODELS.getFirst()));
            stage.put("fourthStatus", fourth.status());
            stage.put("fourthCode", fourth.body().path("error").path("code").asText());
            List<Map<String, Object>> results = new ArrayList<>();
            for (var future : running) results.add(future.get(260, TimeUnit.SECONDS));
            stage.put("requests", results);
            stage.put("passed", full && fourth.status() == 429
                    && "USER_MODEL_CONCURRENCY_LIMIT".equals(stage.get("fourthCode"))
                    && results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("valid"))));
        }
        saveReport();
    }

    /** 校验真实 Provider、四要素和非空结果；仅保存安全元数据与合成场景编号。 */
    private Map<String, Object> enhance(Session session, String model, int sample) {
        return invoke(session, model, sample, Operation.DIRECT);
    }

    /** 除协议与四要素外，核对上下文片段和仅存在于文件里的规则代号是否进入最终结果。 */
    private Map<String, Object> invoke(Session session, String model, int sample, Operation operation) {
        long start = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sample", session.sample);
        result.put("contextKind", session.kind);
        result.put("rawPromptCharacters", session.rawPrompt.length());
        result.put("model", model);
        result.put("valid", false);
        try {
            Map<String, Object> body = payload(session, model);
            String endpoint = "/api/v1/optimizations";
            if (operation == Operation.PLAN) {
                session.plan = null;
                endpoint += "/plan";
                body = Map.of("rawPrompt", session.rawPrompt, "modelId", model, "contextDescription", DESCRIPTION,
                        "planningContext", session.contextReference, "conversationHistory", List.of());
            } else if (operation == Operation.CONFIRMED) {
                body.put("planConfirmation", confirmation(session));
            }
            Reply reply = call(session, "POST", endpoint, body);
            result.put("status", reply.status());
            result.put("httpSucceeded", reply.status() == 200);
            result.put("requestId", reply.body().path("requestId").asText());
            result.put("errorCode", reply.body().path("error").path("code").asText());
            result.put("errorMessage", reply.body().path("error").path("message").asText());
            JsonNode data = reply.body().path("data");
            JsonNode actual = data.path("provider");
            result.put("provider", actual);
            boolean real = actual.has("mock") && !actual.path("mock").asBoolean();
            if (operation == Operation.PLAN) {
                boolean valid = reply.status() == 200 && real && !data.path("planId").asText().isBlank()
                        && data.path("questions").isArray() && data.path("questions").size() <= 8
                        && session.contextReference.equals(data.path("planningContext"));
                result.put("valid", valid);
                result.put("questionCount", data.path("questions").size());
                if (valid) session.plan = data;
            } else {
                boolean sections = Set.of("BACKGROUND", "TASK", "OUTPUT", "CONSTRAINTS").stream().allMatch(type -> {
                    for (JsonNode section : data.path("sections")) {
                        if (type.equals(section.path("type").asText()) && !section.path("content").asText().isBlank()) return true;
                    }
                    return false;
                });
                String output = data.path("optimizedPrompt").asText();
                JsonNode snippets = data.path("contextReport").path("fileSnippets");
                boolean contextPresent = snippets.isArray() && !snippets.isEmpty() && snippets.toString().contains(session.marker);
                boolean markerPresent = output.contains(session.marker);
                result.put("valid", reply.status() == 200 && real && sections && !output.isBlank()
                        && contextPresent && markerPresent);
                result.put("contextFileCount", snippets.size());
                result.put("contextMarkerPresent", contextPresent);
                result.put("outputMarkerPresent", markerPresent);
                result.put("analysisStatus", data.path("contextReport").path("analysisStatus").asText());
                result.put("outputCharacters", output.length());
            }
        } catch (Exception failure) {
            result.put("errorType", failure.getClass().getSimpleName());
        }
        result.put("elapsedMs", (System.nanoTime() - start) / 1_000_000L);
        return result;
    }

    private Map<String, Object> payload(Session session, String model) {
        return new LinkedHashMap<>(Map.of("rawPrompt", session.rawPrompt, "modelId", model,
                "context", session.context, "conversationHistory", List.of()));
    }

    /** 两种上下文分别均匀分配给 Flash 和 Pro，避免混合模型阶段只覆盖一组资料。 */
    private String selectedModel(String model, Session session) {
        return "mixed".equals(model) ? MODELS.get((session.sample / 2) % MODELS.size()) : model;
    }

    /** 合成场景的未知项明确保持待确认；不自动把模型推荐的假设当作事实。 */
    private Map<String, Object> confirmation(Session session) {
        assertThat(session.plan).as("确认必须使用本账号实际生成的计划").isNotNull();
        List<Map<String, String>> answers = new ArrayList<>();
        for (JsonNode question : session.plan.path("questions")) {
            answers.add(Map.of("questionId", question.path("id").asText(), "question", question.path("question").asText(),
                    "answer", "采用上传资料与原始需求中已经明确的要求；该问题缺少的具体数值、日期或选择尚未确定，请在最终提示词中保留待确认，不代为决策。"));
        }
        return Map.of("planId", session.plan.path("planId").asText(), "planningContext", session.contextReference, "answers", answers);
    }

    private long historyCount(Session session) throws Exception {
        Reply response = call(session, "GET", "/api/v1/optimization-history?current=1&size=1", null);
        assertStatus(response, 200);
        return response.body().path("data").path("total").asLong(-1);
    }

    private void assertStatus(Reply response, int expected) {
        assertThat(response.status()).as("HTTP 状态，错误代码 %s", response.body().path("error").path("code").asText())
                .isEqualTo(expected);
    }

    /** 只查询本次测试明确创建的租约键，不扫描开发环境数据。 */
    private boolean awaitCount(String key, long expected, Duration duration) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        do {
            if (Long.valueOf(expected).equals(redis.opsForZSet().zCard(key))) return true;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private long percentile(List<Long> sorted, double proportion) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * proportion) - 1));
    }

    private void saveReport() throws java.io.IOException {
        Files.writeString(OUTPUT.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private record Account(UUID userId, UUID tenantId, UUID workspaceId, String email) { }
    private record Reply(int status, JsonNode body, long elapsedMs) { }
    private enum Operation { DIRECT, PLAN, CONFIRMED }
    private static final class Session {
        private final UUID userId;
        private final Map<String, String> cookies = Collections.synchronizedMap(new LinkedHashMap<>());
        private String csrf = "";
        private int sample;
        private String rawPrompt;
        private String marker;
        private String kind;
        private Map<String, Object> context;
        private JsonNode contextReference;
        private JsonNode plan;
        private String documentId = "";
        private boolean ownsDocument;
        private Session(UUID userId) { this.userId = userId; }
        /** 同账号跨设备共用资料，但每个设备持有独立登录会话。 */
        private void copyContext(Session source) {
            sample = source.sample;
            rawPrompt = source.rawPrompt;
            marker = source.marker;
            kind = source.kind;
            context = source.context;
            contextReference = source.contextReference;
            documentId = source.documentId;
        }
    }

    /** 采样器不改变请求和连接行为；CPU 包含本 JVM 的平台和 HTTP 驱动开销。 */
    private final class Monitor implements AutoCloseable {
        private final java.util.concurrent.ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        private final com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        private final long initialCpu = os.getProcessCpuTime();
        private final long initialTime = System.nanoTime();
        private int upstreamPeak;
        private int connectionsPeak;
        private int waitingPeak;
        private long heapPeak;
        private int samplingErrors;
        private Monitor() { sampler.scheduleAtFixedRate(this::sample, 0, 50, TimeUnit.MILLISECONDS); }
        private synchronized void sample() {
            try {
                upstreamPeak = Math.max(upstreamPeak, redis.opsForZSet().zCard(PREFIX + ":global").intValue());
                heapPeak = Math.max(heapPeak, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
                connectionsPeak = Math.max(connectionsPeak, pool.getActiveConnections());
                waitingPeak = Math.max(waitingPeak, pool.getThreadsAwaitingConnection());
            } catch (Exception ignored) { samplingErrors++; }
        }
        private synchronized Map<String, Object> snapshot() {
            return Map.of("upstreamPeak", upstreamPeak, "activeDbConnectionsPeak", connectionsPeak,
                    "waitingDbConnectionsPeak", waitingPeak, "heapPeakBytes", heapPeak,
                    "averageCpuCores", (double) (os.getProcessCpuTime() - initialCpu) / (System.nanoTime() - initialTime),
                    "samplingErrors", samplingErrors);
        }
        @Override public void close() { sampler.shutdownNow(); }
    }

    /** 只接收生产 ModelCallLogger 已脱敏的事件，不保存其他日志或上游正文。 */
    private static final class ModelEvents extends AppenderBase<ILoggingEvent> {
        private static final Pattern FIELD = Pattern.compile("([A-Za-z]+)=([^ ]+)");
        private final List<Map<String, String>> events = new CopyOnWriteArrayList<>();
        @Override protected void append(ILoggingEvent event) {
            Map<String, String> entry = new LinkedHashMap<>();
            var matcher = FIELD.matcher(event.getFormattedMessage());
            while (matcher.find()) entry.put(matcher.group(1), matcher.group(2));
            events.add(entry);
        }
    }
}
