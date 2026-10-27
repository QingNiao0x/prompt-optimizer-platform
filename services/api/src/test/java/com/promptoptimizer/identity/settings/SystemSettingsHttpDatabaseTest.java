package com.promptoptimizer.identity.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.config.MybatisPlusConfiguration;
import com.promptoptimizer.common.exception.GlobalExceptionHandler;
import com.promptoptimizer.identity.controller.AuthenticationController;
import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.domain.UserIdentityType;
import com.promptoptimizer.identity.infrastructure.registration.RegistrationProperties;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import com.promptoptimizer.identity.security.DatabaseUserDetailsService;
import com.promptoptimizer.identity.security.SecurityConfiguration;
import com.promptoptimizer.identity.security.SecurityContextCurrentActor;
import com.promptoptimizer.identity.security.SecurityErrorWriter;
import com.promptoptimizer.identity.service.LoginCaptchaService;
import com.promptoptimizer.identity.service.VerificationEmailSender;
import com.promptoptimizer.identity.service.impl.AuthenticationServiceImpl;
import com.promptoptimizer.identity.service.impl.LoginFailureGuard;
import com.promptoptimizer.identity.service.impl.SmsAccountTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 真实PostgreSQL迁移、Mapper、事务和Spring Security请求验收；只有本次专用回环库可启用。
 * 模拟邮件投递与登录防刷外部依赖，不发送真实邮件，不读取既有用户凭据或操作业务数据库。
 * @author QingNiao
 * @since 0.1.0
 */
@EnabledIfEnvironmentVariable(named = "SETTINGS_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55448/system_settings_acceptance")
@SpringBootTest(classes = SystemSettingsHttpDatabaseTest.TestApplication.class, properties = {
        "spring.profiles.active=settings-acceptance", "spring.datasource.url=${SETTINGS_TEST_DB_URL}",
        "spring.datasource.username=settings_test", "spring.datasource.password=", "spring.datasource.hikari.maximum-pool-size=3",
        "spring.session.store-type=none", "app.security.registration.enabled=true", "app.security.registration.delivery-mode=smtp",
        "app.security.registration.verification-secret=synthetic-settings-verification-secret-for-tests-only",
        "app.security.registration.email-hourly-limit=5", "app.security.registration.ip-hourly-limit=200",
        "decorator.datasource.enabled=false", "logging.level.com.promptoptimizer=WARN"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SystemSettingsHttpDatabaseTest {
    private static final String PASSWORD = "SyntheticSettings1";
    private static final String SETTINGS = "/api/v1/me/settings";
    private static final String SCHEMA = "settings_test_" + UUID.randomUUID().toString().replace("-", "");

    /** 每次验收使用全新schema，既不删除旧证据，也不让上次的合成管理员干扰最后管理员规则。 */
    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IdentityProvisioningMapper provision;
    @Autowired UserIdentityMapper identities;
    @Autowired SystemSettingsFixtureMapper fixture;
    @Autowired PasswordEncoder encoder;
    @Autowired DatabaseUserDetailsService userDetails;
    @Autowired SmsAccountTransactions phoneTransactions;
    @MockBean VerificationEmailSender sender;
    @MockBean LoginCaptchaService captcha;
    @MockBean LoginFailureGuard guard;
    @MockBean AnalyticsEventService analytics;
    private final Map<String, String> delivered = new ConcurrentHashMap<>();

    @BeforeEach
    void captureSyntheticMailOnly() {
        delivered.clear();
        when(sender.supportsBinding()).thenReturn(true);
        doAnswer(call -> { delivered.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(sender).sendBinding(anyString(), anyString(), any());
    }

    @Test
    void ordinaryUserPersistsProfileLocaleAndCannotChooseAnotherAccount() throws Exception {
        var account = account(false); var first = login(account.email()); var second = login(account.email());
        assertStatus(send(new Browser(), get(SETTINGS), false), 401);
        assertStatus(send(first, patch(SETTINGS + "/profile").content(body(Map.of("displayName", "不能绕过CSRF"))), false), 403);
        assertStatus(send(first, patch(SETTINGS + "/profile").content(body(Map.of("displayName", "  星河🌟  ", "userId", UUID.randomUUID()))), true), 200);
        assertThat(data(send(first, get("/api/v1/auth/me"), false)).path("displayName").asText()).isEqualTo("星河🌟");
        assertThat(data(send(second, get("/api/v1/auth/me"), false)).path("displayName").asText()).isEqualTo("星河🌟");
        assertStatus(send(first, patch(SETTINGS + "/preferences").content(body(Map.of("locale", "en"))), true), 200);
        assertThat(data(send(second, get(SETTINGS), false)).path("locale").asText()).isEqualTo("en");
        assertStatus(send(first, patch(SETTINGS + "/preferences").content(body(Map.of("locale", "unsupported"))), true), 400);
        assertThat(fixture.snapshot(account.user()).version()).isZero();
    }

    @Test
    void logoutAllRevokesBothDevicesButLeavesAnotherAccountAndHistory() throws Exception {
        var account = account(false); var other = account(false);
        var first = login(account.email()); var second = login(account.email()); var unrelated = login(other.email());
        assertStatus(send(first, post(SETTINGS + "/logout-all").content(password("wrong-password")), true), 403);
        assertStatus(send(second, get(SETTINGS), false), 200);
        assertStatus(send(first, post(SETTINGS + "/logout-all").content(password(PASSWORD)), true), 200);
        assertStatus(send(first, get("/api/v1/auth/me"), false), 401);
        assertStatus(send(second, get("/api/v1/auth/me"), false), 401);
        assertStatus(send(unrelated, get(SETTINGS), false), 200);
        assertThat(fixture.snapshot(account.user())).extracting("histories", "memberships", "version").containsExactly(1L, 1L, 1L);
        assertStatus(send(login(account.email()), get(SETTINGS), false), 200);
    }

    @Test
    void usernameChangeNormalizesAndPreservesRevokedOwnership() throws Exception {
        var account = account(false); var other = account(false); var browser = login(account.email());
        String occupied = "taken-" + UUID.randomUUID().toString().substring(0, 12);
        provision.insertUserIdentity(UUID.randomUUID(), other.user(), "USERNAME", "local", occupied, occupied, "ACTIVE", OffsetDateTime.now());
        assertStatus(send(browser, patch(SETTINGS + "/username").content(body(Map.of("username", occupied.toUpperCase(), "currentPassword", PASSWORD))), true), 409);
        assertStatus(send(browser, get(SETTINGS), false), 200);
        String username = "user-" + UUID.randomUUID().toString().substring(0, 12);
        assertStatus(send(browser, patch(SETTINGS + "/username").content(body(Map.of("username", "  " + username.toUpperCase() + "  ", "currentPassword", PASSWORD))), true), 200);
        assertStatus(send(browser, get(SETTINGS), false), 401);
        var byUsername = login(username);
        String replacement = username + "-new";
        assertStatus(send(byUsername, patch(SETTINGS + "/username").content(body(Map.of("username", replacement, "currentPassword", PASSWORD))), true), 200);
        assertThat(identities.selectByLoginKey(UserIdentityType.USERNAME, "local", username).getStatus()).isEqualTo(UserIdentityStatus.REVOKED);
        assertStatus(send(login(other.email()), patch(SETTINGS + "/username").content(body(Map.of("username", username, "currentPassword", PASSWORD))), true), 409);
    }

    @Test
    void emailChallengeRejectsCrossBrowserAndPersistsFiveBadAttempts() throws Exception {
        var account = account(false); var first = login(account.email()); var second = login(account.email());
        String target = "new-" + UUID.randomUUID() + "@example.test";
        var receipt = challenge(first, target);
        String id = receipt.path("challengeId").asText();
        String wrong = delivered.get(target).equals("000000") ? "111111" : "000000";
        assertStatus(email(second, target, id, delivered.get(target)), 400);
        for (int attempt = 0; attempt < 5; attempt++) assertStatus(email(first, target, id, wrong), 400);
        assertThat(fixture.challenge(UUID.fromString(id))).extracting("attempts", "status").containsExactly(5, "FAILED");
        assertStatus(email(first, target, id, delivered.get(target)), 400);
        assertThat(fixture.snapshot(account.user()).version()).isZero();
        assertStatus(send(first, post(SETTINGS + "/email-challenges").content(body(Map.of("email", target, "currentPassword", PASSWORD))), true), 429);
    }

    @Test
    void verifiedEmailConflictRollsBackAndSuccessfulBindingConsumesWithIdentity() throws Exception {
        var account = account(false); var other = account(false); var browser = login(account.email());
        var conflict = challenge(browser, other.email());
        String conflictId = conflict.path("challengeId").asText();
        assertStatus(email(browser, other.email(), conflictId, delivered.get(other.email())), 409);
        assertThat(fixture.challenge(UUID.fromString(conflictId)).status()).isEqualTo("SENT");
        assertThat(identities.selectByLoginKey(UserIdentityType.EMAIL, "local", other.email()).getUserId()).isEqualTo(other.user());
        assertThat(fixture.snapshot(account.user()).version()).isZero();
        // 新合成账号隔离发送冷却；不重置预算来让重试绕过正式限制。
        var changing = account(false); var first = login(changing.email()); var second = login(changing.email());
        String target = "bound-" + UUID.randomUUID() + "@example.test";
        var receipt = challenge(first, target); String id = receipt.path("challengeId").asText();
        assertStatus(email(first, target.toUpperCase(), id, delivered.get(target)), 200);
        assertThat(fixture.challenge(UUID.fromString(id)).status()).isEqualTo("CONSUMED");
        assertThat(identities.selectByLoginKey(UserIdentityType.EMAIL, "local", changing.email()).getStatus()).isEqualTo(UserIdentityStatus.REVOKED);
        assertThat(identities.selectByLoginKey(UserIdentityType.EMAIL, "local", target).getUserId()).isEqualTo(changing.user());
        assertStatus(send(second, get(SETTINGS), false), 401);
        var fresh = login(target);
        assertStatus(email(fresh, target, id, delivered.get(target)), 400);
        assertThat(fixture.snapshot(changing.user())).extracting("histories", "memberships", "version").containsExactly(1L, 1L, 1L);
    }

    @Test
    void expiredChallengeAndRevokedLoginIdentityCannotBeReused() throws Exception {
        var account = account(false); var browser = login(account.email());
        String target = "expired-" + UUID.randomUUID() + "@example.test";
        String id = challenge(browser, target).path("challengeId").asText();
        fixture.expire(UUID.fromString(id));
        assertStatus(email(browser, target, id, delivered.get(target)), 400);
        fixture.revoke(account.user());
        assertStatus(send(browser, get("/api/v1/auth/me"), false), 401);
    }

    @Test
    void deactivateRequiresConfirmationRevokesDevicesAndPreservesOwnedResources() throws Exception {
        var account = account(false); var first = login(account.email()); var second = login(account.email());
        assertStatus(send(first, post(SETTINGS + "/deactivate").content(body(Map.of("currentPassword", PASSWORD, "confirmation", "NO"))), true), 400);
        assertStatus(send(second, get(SETTINGS), false), 200);
        assertStatus(send(first, post(SETTINGS + "/deactivate").content(body(Map.of("currentPassword", PASSWORD, "confirmation", "DELETE"))), true), 200);
        assertStatus(send(first, get(SETTINGS), false), 401); assertStatus(send(second, get(SETTINGS), false), 401);
        var saved = fixture.snapshot(account.user());
        assertThat(saved).extracting("status", "identities", "histories", "memberships").containsExactly("DISABLED", 0L, 1L, 1L);
        assertThat(saved.deleted()).isNotNull();
        assertThat(identities.selectByLoginKey(UserIdentityType.EMAIL, "local", account.email()).getUserId()).isEqualTo(account.user());
    }

    @Test
    void lastActivePlatformAdminCannotSelfDeactivate() throws Exception {
        var account = account(true); var browser = login(account.email());
        assertThat(data(send(browser, get(SETTINGS), false)).path("accountDeletionAvailable").asBoolean()).isFalse();
        assertStatus(send(browser, post(SETTINGS + "/deactivate").content(body(Map.of("currentPassword", PASSWORD, "confirmation", "DELETE"))), true), 409);
        assertThat(fixture.snapshot(account.user())).extracting("status", "version").containsExactly("ACTIVE", 0L);
    }

    @Test
    void phoneBindingRechecksDatabaseVersionAfterAllDeviceRevocation() throws Exception {
        var account = account(false);
        var inFlight = (com.promptoptimizer.identity.security.AuthenticatedUser) userDetails.loadUserByUsername(account.email());
        phoneTransactions.checkBindingActor(inFlight, PASSWORD);
        var browser = login(account.email());
        assertStatus(send(browser, post(SETTINGS + "/logout-all").content(password(PASSWORD)), true), 200);
        // 模拟云端核验期间发生撤销：即使旧请求此前通过过滤器，也不能在后续事务中绑定。
        assertThatThrownBy(() -> phoneTransactions.checkBindingActor(inFlight, PASSWORD))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        assertThat(fixture.snapshot(account.user()).version()).isEqualTo(1L);
    }

    /** 合成资源仅写入专用验收库，所有场景内的多设备共用同一账号。 */
    private Account account(boolean admin) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), workspace = UUID.randomUUID();
        String email = "settings-" + user + "@example.test";
        provision.insertTenant(tenant, "合成设置租户");
        provision.insertUserAccount(user, tenant, email, "合成用户", encoder.encode(PASSWORD), admin ? "PLATFORM_ADMIN" : "USER");
        provision.insertWorkspace(workspace, tenant, "合成工作区", "仅用于隔离验收", user);
        provision.insertWorkspaceMember(workspace, user, "OWNER");
        provision.insertUserIdentity(UUID.randomUUID(), user, "EMAIL", "local", email, email, "ACTIVE", OffsetDateTime.now());
        fixture.history(UUID.randomUUID(), tenant, workspace, user);
        return new Account(user, email);
    }

    private Browser login(String identifier) throws Exception {
        var browser = new Browser();
        assertStatus(send(browser, post("/api/v1/auth/login").content(body(Map.of("identifier", identifier, "password", PASSWORD, "captcha", "ABCD"))), true), 200);
        return browser;
    }

    private JsonNode challenge(Browser browser, String email) throws Exception {
        var response = send(browser, post(SETTINGS + "/email-challenges").content(body(Map.of("email", email, "currentPassword", PASSWORD))), true);
        assertStatus(response, 200);
        assertThat(response.getContentAsString()).doesNotContain(delivered.get(email));
        return data(response);
    }

    private org.springframework.mock.web.MockHttpServletResponse email(Browser browser, String email, String challenge, String code) throws Exception {
        return send(browser, post(SETTINGS + "/email").content(body(Map.of("email", email, "challengeId", challenge,
                "verificationCode", code, "currentPassword", PASSWORD))), true);
    }

    private String password(String value) throws Exception { return body(Map.of("currentPassword", value)); }
    private String body(Object value) throws Exception { return json.writeValueAsString(value); }
    private JsonNode data(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }

    /** 使用真正的Cookie双提交，不由spring-security-test伪造认证或跳过CSRF。 */
    private org.springframework.mock.web.MockHttpServletResponse send(Browser browser, MockHttpServletRequestBuilder request, boolean write) throws Exception {
        if (write) {
            var csrf = send(browser, get("/api/v1/auth/csrf"), false);
            request.header("X-XSRF-TOKEN", data(csrf).path("token").asText());
        }
        request.contentType("application/json");
        for (var cookie : browser.cookies.getCookieStore().getCookies()) if (!cookie.hasExpired()) {
            request.cookie(new jakarta.servlet.http.Cookie(cookie.getName(), cookie.getValue()));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        browser.cookies.put(URI.create("http://localhost/"), Map.of("Set-Cookie", response.getHeaders("Set-Cookie")));
        return response;
    }

    private void assertStatus(org.springframework.mock.web.MockHttpServletResponse response, int expected) {
        // 诊断仅报告状态和稳定错误码，不把测试验证码或Cookie写入断言输出。
        assertThat(response.getStatus()).isEqualTo(expected);
    }

    private record Account(UUID user, String email) { }
    private static final class Browser { private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL); }

    /** 隔离装配真实数据库与安全链，Spring Session只替换存储，不替换请求过滤器。 */
    @Configuration(proxyBeanMethods = false)
    @Profile("settings-acceptance")
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class, MailSenderAutoConfiguration.class})
    @EnableSpringHttpSession
    @EnableConfigurationProperties(RegistrationProperties.class)
    @Import({MybatisPlusConfiguration.class, SecurityConfiguration.class, SecurityErrorWriter.class,
            SecurityContextCurrentActor.class, DatabaseUserDetailsService.class, AuthenticationServiceImpl.class,
            AuthenticationController.class, GlobalExceptionHandler.class, AccountSettingsController.class,
            AccountSettingsService.class, AccountSettingsTransactions.class, AccountSessionValidator.class, SmsAccountTransactions.class})
    static class TestApplication {
        @Bean MapSessionRepository sessionRepository() { return new MapSessionRepository(new ConcurrentHashMap<>()); }
    }
}
