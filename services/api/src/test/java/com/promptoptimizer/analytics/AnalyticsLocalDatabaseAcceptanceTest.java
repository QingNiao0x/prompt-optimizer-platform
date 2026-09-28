package com.promptoptimizer.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.mapper.AuditEventMapper;
import com.promptoptimizer.analytics.support.AnalyticsAcceptanceMapper;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import com.promptoptimizer.identity.security.BootstrapAdminAccountInitializer;
import com.promptoptimizer.identity.security.BootstrapUserPasswordInitializer;
import com.promptoptimizer.identity.service.LoginCaptchaService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在已初始化的本地 PostgreSQL 上串联真实身份认证、业务 Controller、审计写入和统计查询。
 * 禁止自动迁移和初始化已有账号；随机测试数据只在同线程事务内存在，并核查回滚结果。
 * Provider 固定为 Mock，测试证明事件与统计链路，不代替真实模型业务质量或浏览器验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/prompt_optimizer",
        "spring.flyway.enabled=false",
        "spring.session.store-type=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "app.security.login-guard.require-redis=false",
        "app.provider.mode=mock",
        "app.analytics.zone-id=Asia/Shanghai",
        "app.analytics.trusted-proxies=",
        "app.analytics.recharge.enabled=false",
        "decorator.datasource.enabled=false",
        "logging.level.com.promptoptimizer=WARN"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsLocalDatabaseAcceptanceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PROMPT = "为 Java 用户服务添加登录接口，使用邮箱和密码登录，输出接口和测试方案。";
    private static final String CLIENT_IP = "198.51.100.42";
    private static final String[] ADMIN_PATHS = {"dashboard", "usage-ranking", "operations"};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;
    @Autowired private IdentityProvisioningMapper provisioning;
    @Autowired private AuditEventMapper audit;
    @Autowired private AnalyticsAcceptanceMapper inspection;
    // ApplicationRunner 在测试事务之前运行，必须明确替换，不能依赖本机初始化配置为空。
    @MockBean private BootstrapAdminAccountInitializer adminInitializer;
    @MockBean private BootstrapUserPasswordInitializer userInitializer;

    private final List<Account> accounts = new ArrayList<>();
    private Account admin;
    private Account ordinary;
    private String password;

    @BeforeEach
    void createTransactionalAccounts() {
        password = UUID.randomUUID() + "Aa!";
        String hash = encoder.encode(password);
        admin = createAccount("PLATFORM_ADMIN", hash);
        ordinary = createAccount("USER", hash);
    }

    @AfterEach
    void noCredentialsOrSourceTextInLogs(CapturedOutput output) {
        SecurityContextHolder.clearContext();
        assertThat(output.getAll().contains(password)).as("不得记录测试登录凭据").isFalse();
        assertThat(output.getAll().contains(PROMPT)).as("不得记录原始提示词").isFalse();
    }

    @AfterTransaction
    void allAcceptanceRowsAreRolledBack() {
        for (Account account : accounts) {
            assertThat(inspection.remainingRows(account.userId(), account.tenantId(), account.workspaceId()))
                    .as("测试账户及其审计、历史、身份和工作区记录均必须回滚").isZero();
        }
    }

    @Test
    void loginPlanEnhanceExportAndLogoutMatchPersistedAuditAndDashboard() throws Exception {
        Login member = login(ordinary);
        event(member, "APP_VISIT");
        event(member, "APP_VISIT");
        JsonNode prepared = data(mvc.perform(write("/api/v1/context/planning", member)
                        .content(json.writeValueAsString(Map.of("rawPrompt", PROMPT,
                                "context", Map.of("customDescription", "Java 21 用户服务", "files", List.of())))))
                .andExpect(status().isOk()).andReturn());
        Map<String, String> reference = Map.of("contextId", prepared.path("contextId").asText(),
                "version", prepared.path("version").asText());
        JsonNode plan = data(mvc.perform(write("/api/v1/optimizations/plan", member)
                        .content(json.writeValueAsString(Map.of("rawPrompt", PROMPT, "planningContext", reference,
                                "contextDescription", "Java 21 用户服务"))))
                .andExpect(status().isOk()).andReturn());
        ObjectNode confirmation = json.createObjectNode().put("planId", plan.path("planId").asText());
        confirmation.set("planningContext", json.valueToTree(reference));
        var answers = confirmation.putArray("answers");
        for (JsonNode question : plan.path("questions")) {
            answers.addObject().put("questionId", question.path("id").asText())
                    .put("question", question.path("question").asText())
                    .put("answer", "使用现有 Java 用户服务实现基础邮箱密码登录，输出接口和测试方案。");
        }
        ObjectNode optimization = json.createObjectNode().put("rawPrompt", PROMPT);
        optimization.putObject("context").put("customDescription", "Java 21 用户服务");
        optimization.set("planConfirmation", confirmation);
        JsonNode result = data(mvc.perform(write("/api/v1/optimizations", member)
                        .content(json.writeValueAsString(optimization)))
                .andExpect(status().isOk()).andReturn());
        assertThat(result.path("optimizedPrompt").asText()).isNotBlank();
        event(member, "RESULT_EXPORTED");
        String authenticationSessionId = member.session().getId();
        mvc.perform(write("/api/v1/auth/logout", member)).andExpect(status().isOk());
        assertThat(inspection.eventTypes(ordinary.userId())).containsExactly(
                "LOGIN", "APP_VISIT", "APP_VISIT", "CONTEXT_PREPARED", "PLAN_CREATED",
                "OPTIMIZATION_SUBMITTED", "RESULT_EXPORTED", "LOGOUT");

        String loginCorrelation = null;
        for (String rawDetails : inspection.eventDetails(ordinary.userId())) {
            JsonNode details = json.readTree(rawDetails);
            assertThat(details.path("clientIp").asText()).isEqualTo(CLIENT_IP);
            assertThat(details.path("deviceType").asText()).isEqualTo("DESKTOP");
            assertThat(details.path("requestId").asText()).isNotBlank();
            if (loginCorrelation == null) loginCorrelation = details.path("loginSessionId").asText();
            assertThat(details.path("loginSessionId").asText()).isEqualTo(loginCorrelation);
            assertThat(rawDetails.contains(password)).isFalse();
            assertThat(rawDetails.contains(PROMPT)).isFalse();
            // MockHttpSession 的 ID 可能只有一位，不能对整段 JSON 使用子串匹配。
            assertThat(details.path("loginSessionId").asText().equals(authenticationSessionId)).isFalse();
            assertThat(details.has("sessionId")).isFalse();
            assertThat(rawDetails.contains(member.token().getValue())).isFalse();
        }
        Login administrator = login(admin);
        LocalDate today = LocalDate.now(ZONE);
        JsonNode dashboard = dashboard(administrator, "CUSTOM", today.minusDays(1), today.plusDays(1), ordinary.userId());
        assertThat(dashboard.path("accessCount").asInt()).isEqualTo(2);
        for (String name : List.of("uniqueVisitorCount", "activeUserCount", "actualUserCount", "registeredAccountCount")) {
            assertThat(dashboard.path(name).asInt()).as(name).isEqualTo(1);
        }
        JsonNode operations = operations(administrator, today.minusDays(1), today.plusDays(1), ordinary.userId(), 1, 10, null);
        assertThat(operations.path("total").asInt()).isEqualTo(8);
        assertThat(operations.path("records").size()).isEqualTo(8);
        JsonNode rank = ranking(administrator, today, ordinary.userId());
        assertThat(rank.path("items").get(0).path("operationCount").asInt()).isEqualTo(4);
        assertThat(rank.path("items").get(0).path("loginCount").asInt()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TODAY", "YESTERDAY", "THIS_WEEK", "THIS_MONTH", "LAST_MONTH", "CUSTOM"})
    void dateBoundariesDistinctUsersZeroDaysAndPaginationAgree(String range) throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = switch (range) {
            case "YESTERDAY" -> today.minusDays(1);
            case "THIS_WEEK" -> today.minusDays(today.getDayOfWeek().getValue() - 1L);
            case "THIS_MONTH" -> today.withDayOfMonth(1);
            case "LAST_MONTH" -> today.withDayOfMonth(1).minusMonths(1);
            case "CUSTOM" -> LocalDate.of(2024, 2, 28);
            default -> today;
        };
        LocalDate until = switch (range) {
            case "YESTERDAY" -> today;
            case "LAST_MONTH" -> today.withDayOfMonth(1);
            case "CUSTOM" -> LocalDate.of(2024, 3, 2);
            default -> today.plusDays(1);
        };
        OffsetDateTime start = from.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime end = until.atStartOfDay(ZONE).toOffsetDateTime();
        inspection.setCreatedAt(ordinary.userId(), start);
        seedEvent(ordinary, AnalyticsEventType.APP_VISIT, start.minusNanos(1000));
        seedEvent(ordinary, AnalyticsEventType.APP_VISIT, start);
        seedEvent(ordinary, AnalyticsEventType.APP_VISIT, start.plusNanos(1000));
        seedEvent(ordinary, AnalyticsEventType.LOGIN, start.plusSeconds(1));
        seedEvent(ordinary, AnalyticsEventType.OPTIMIZATION_SUBMITTED, end.minusNanos(1000));
        seedEvent(ordinary, AnalyticsEventType.APP_VISIT, end);
        seedEvent(admin, AnalyticsEventType.APP_VISIT, start);
        Login administrator = login(admin);
        JsonNode view = dashboard(administrator, range, from, until.minusDays(1), ordinary.userId());
        assertThat(view.path("period").path("fromDate").asText()).isEqualTo(from.toString());
        assertThat(view.path("period").path("toDateInclusive").asText()).isEqualTo(until.minusDays(1).toString());
        assertThat(view.path("period").path("zoneId").asText()).isEqualTo(ZONE.getId());
        assertThat(view.path("accessCount").asInt()).isEqualTo(2);
        for (String key : List.of("newAccountCount", "uniqueVisitorCount", "activeUserCount", "actualUserCount")) {
            assertThat(view.path(key).asInt()).as(key).isEqualTo(1);
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, until);
        assertThat(view.path("dailyMetrics").size()).isEqualTo(days);
        BigDecimal expectedAverage = BigDecimal.valueOf(days == 1 ? 1 : 2)
                .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        assertThat(view.path("averageDailyActiveUsers").decimalValue()).isEqualByComparingTo(expectedAverage);
        JsonNode first = operations(administrator, from, until.minusDays(1), ordinary.userId(), 1, 2, null);
        JsonNode second = operations(administrator, from, until.minusDays(1), ordinary.userId(), 2, 2, null);
        assertThat(first.path("total").asInt()).isEqualTo(4);
        assertThat(first.path("pages").asInt()).isEqualTo(2);
        assertThat(first.path("records").size()).isEqualTo(2);
        assertThat(second.path("records").size()).isEqualTo(2);
        HashSet<String> ids = new HashSet<>();
        first.path("records").forEach(row -> ids.add(row.path("eventId").asText()));
        second.path("records").forEach(row -> ids.add(row.path("eventId").asText()));
        assertThat(ids).hasSize(4);
        JsonNode logins = operations(administrator, from, until.minusDays(1), ordinary.userId(), 1, 10, "LOGIN");
        assertThat(logins.path("total").asInt()).isEqualTo(1);
        assertThat(logins.path("records").get(0).path("eventType").asText()).isEqualTo("LOGIN");
        assertThat(ranking(administrator, until.minusDays(1), ordinary.userId()).path("items").get(0)
                .path("operationCount").asInt()).isEqualTo(1);
        assertThat(dashboard(administrator, range, from, until.minusDays(1), UUID.randomUUID())
                .path("accessCount").asInt()).isZero();
    }

    @Test
    void realDatabaseRoleRevocationAndDisabledAccountsRejectAllAdminEndpoints() throws Exception {
        for (String path : ADMIN_PATHS) {
            mvc.perform(adminQuery(path)).andExpect(status().isUnauthorized());
        }
        Login member = login(ordinary);
        for (String path : ADMIN_PATHS) {
            mvc.perform(adminQuery(path).session(member.session())).andExpect(status().isForbidden());
        }
        Login administrator = login(admin);
        assertThat(inspection.setAccess(admin.userId(), "USER", "ACTIVE")).isEqualTo(1);
        for (String path : ADMIN_PATHS) {
            mvc.perform(adminQuery(path).session(administrator.session())).andExpect(status().isForbidden());
        }
        inspection.setAccess(admin.userId(), "PLATFORM_ADMIN", "DISABLED");
        for (String path : ADMIN_PATHS) {
            mvc.perform(adminQuery(path).session(administrator.session())).andExpect(status().isForbidden());
        }
    }

    @Test
    void invalidDatesFiltersAndPaginationReturnValidationErrors() throws Exception {
        Login administrator = login(admin);
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/api/v1/admin/analytics/dashboard").param("range", "UNKNOWN"),
                get("/api/v1/admin/analytics/dashboard").param("range", "CUSTOM")
                        .param("fromDate", "2026-02-30").param("toDate", "2026-03-01"),
                get("/api/v1/admin/analytics/dashboard").param("range", "CUSTOM")
                        .param("fromDate", "2026-03-02").param("toDate", "2026-03-01"),
                get("/api/v1/admin/analytics/dashboard").param("userId", "not-a-uuid"),
                adminQuery("operations").param("current", "0"),
                adminQuery("operations").param("size", "101"),
                adminQuery("operations").param("eventType", "UNKNOWN"),
                get("/api/v1/admin/analytics/usage-ranking").param("period", "YEAR")
                        .param("date", "2026-09-01"),
                adminQuery("usage-ranking").param("limit", "101"));
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request.session(administrator.session())).andExpect(status().isBadRequest());
        }
        assertThat(inspection.eventTypes(admin.userId())).containsExactly("LOGIN");
    }

    @Test
    void clientCannotForgeBusinessEventsOrIdentityAndInvalidRequestsDoNotAddEvents() throws Exception {
        Login member = login(ordinary);
        mvc.perform(write("/api/v1/analytics/events", member).content("{\"eventType\":\"PLAN_CREATED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write("/api/v1/optimizations", member).content("{\"rawPrompt\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write("/api/v1/analytics/events", member)
                        .header("X-User-Id", admin.userId()).header("X-Forwarded-For", "203.0.113.9")
                        .content("{\"eventType\":\"APP_VISIT\"}"))
                .andExpect(status().isOk());
        assertThat(inspection.eventTypes(ordinary.userId())).containsExactly("LOGIN", "APP_VISIT");
        assertThat(inspection.eventTypes(admin.userId())).isEmpty();
        for (String details : inspection.eventDetails(ordinary.userId())) {
            assertThat(json.readTree(details).path("clientIp").asText()).isEqualTo(CLIENT_IP);
        }
    }

    /** 创建随机独立租户、账户、工作区和邮箱身份，全部加入当前回滚事务。 */
    private Account createAccount(String role, String hash) {
        Account account = new Account(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID() + "@analytics.invalid");
        accounts.add(account);
        provisioning.insertTenant(account.tenantId(), "analytics-acceptance");
        provisioning.insertUserAccount(account.userId(), account.tenantId(), account.email(),
                "analytics-acceptance", hash, role);
        provisioning.insertWorkspace(account.workspaceId(), account.tenantId(), "analytics-acceptance", "", account.userId());
        provisioning.insertWorkspaceMember(account.workspaceId(), account.userId(), "OWNER");
        provisioning.insertUserIdentity(UUID.randomUUID(), account.userId(), "EMAIL", "local", account.email(),
                account.email(), "ACTIVE", OffsetDateTime.now());
        return account;
    }

    /** 使用真实验证码、BCrypt、数据库身份和 Session 登录；禁止在失败时打印请求凭据。 */
    private Login login(Account account) throws Exception {
        Cookie csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/api/v1/auth/captcha").session(session)).andExpect(status().isOk());
        String captcha = (String) session.getAttribute(LoginCaptchaService.ATTRIBUTE);
        MvcResult result = mvc.perform(post("/api/v1/auth/login").session(session).cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).header("User-Agent", "Mozilla/5.0 Windows NT 10.0")
                        .with(request -> { request.setRemoteAddr(CLIENT_IP); return request; })
                        .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "identifier", account.email(), "password", password, "captcha", captcha))))
                .andExpect(status().isOk()).andReturn();
        Cookie rotated = java.util.Arrays.stream(result.getResponse().getCookies())
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()) && cookie.getMaxAge() != 0)
                .reduce((first, last) -> last).orElseThrow();
        return new Login((MockHttpSession) result.getRequest().getSession(false), rotated);
    }

    /** 所有写请求携带服务端签发的会话和 CSRF，模拟保留地址段中的客户端。 */
    private MockHttpServletRequestBuilder write(String path, Login login) {
        return post(path).session(login.session()).cookie(login.token())
                .header("X-XSRF-TOKEN", login.token().getValue()).contentType(APPLICATION_JSON)
                .header("User-Agent", "Mozilla/5.0 Windows NT 10.0")
                .with(request -> { request.setRemoteAddr(CLIENT_IP); return request; });
    }

    private void event(Login login, String type) throws Exception {
        mvc.perform(write("/api/v1/analytics/events", login)
                .content(json.writeValueAsString(Map.of("eventType", type)))).andExpect(status().isOk());
    }

    /** 使用生产事件 Mapper 写边界夹具；不引入测试专用统计 SQL。 */
    private void seedEvent(Account account, AnalyticsEventType type, OffsetDateTime time) {
        audit.insert(UUID.randomUUID(), account.tenantId(), account.userId(), type,
                Map.of("deviceType", "DESKTOP"), time);
    }

    private JsonNode dashboard(Login login, String range, LocalDate from, LocalDate to, UUID userId) throws Exception {
        return data(mvc.perform(get("/api/v1/admin/analytics/dashboard").session(login.session())
                        .param("range", range).param("fromDate", from.toString()).param("toDate", to.toString())
                        .param("userId", userId.toString())).andExpect(status().isOk()).andReturn());
    }

    private JsonNode operations(Login login, LocalDate from, LocalDate to, UUID userId,
                                int current, int size, String type) throws Exception {
        var request = get("/api/v1/admin/analytics/operations").session(login.session())
                .param("fromDate", from.toString()).param("toDate", to.toString()).param("userId", userId.toString())
                .param("current", Integer.toString(current)).param("size", Integer.toString(size));
        if (type != null) request.param("eventType", type);
        return data(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private JsonNode ranking(Login login, LocalDate day, UUID userId) throws Exception {
        return data(mvc.perform(get("/api/v1/admin/analytics/usage-ranking").session(login.session())
                        .param("period", "DAY").param("date", day.toString()).param("userId", userId.toString()))
                .andExpect(status().isOk()).andReturn());
    }

    private MockHttpServletRequestBuilder adminQuery(String path) {
        return get("/api/v1/admin/analytics/" + path).param("range", "TODAY")
                .param("period", "DAY").param("date", "2026-09-01")
                .param("fromDate", "2026-09-01").param("toDate", "2026-09-01");
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private record Account(UUID tenantId, UUID userId, UUID workspaceId, String email) { }
    private record Login(MockHttpSession session, Cookie token) { }
}
