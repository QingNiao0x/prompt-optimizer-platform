package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.common.exception.GlobalExceptionHandler;
import com.promptoptimizer.identity.controller.RegistrationController;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import com.promptoptimizer.identity.service.AccountRegistrationGateway;
import com.promptoptimizer.identity.service.AuthenticationService;
import com.promptoptimizer.identity.service.EmailVerificationStore;
import com.promptoptimizer.identity.service.VerificationEmailSender;
import com.promptoptimizer.identity.service.impl.EmailRegistrationServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.promptoptimizer.identity.service.EmailVerificationStore.IssueResult.*;
import static com.promptoptimizer.identity.service.EmailVerificationStore.VerificationResult.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对隔离 Redis 执行生产 Lua，覆盖邮箱发码与限流；邮件、账户持久化和认证使用模拟对象。
 * 仅显式设置专用端点时运行，不读取业务 Redis 配置，不清空数据库，不覆盖 CSRF 或真实投递验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "EMAIL_REDIS_TEST_URL", matches = "redis://127\\.0\\.0\\.1:56409/0")
class HybridEmailVerificationStoreRedisTest {

    private static final String KEY_PREFIX = "prompt-optimizer:email-registration:v1:";
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RegistrationProperties properties;
    private HybridEmailVerificationStore store;

    /** 固定专用端口，避免误连现有 6379 实例；连接不可用时直接使测试失败。 */
    @BeforeAll
    void connectToDedicatedRedis() {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3))
                .shutdownTimeout(Duration.ZERO)
                .build();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 56409), client);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try (var connection = connectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @AfterAll
    void closeConnectionFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void createStore() {
        properties = new RegistrationProperties();
        properties.setDeliveryMode("smtp");
        properties.setRequireRedis(true);
        properties.setVerificationSecret("synthetic-email-regression-secret-not-for-deployment");
        var beans = new StaticListableBeanFactory();
        beans.addBean("redisTemplate", redis);
        store = new HybridEmailVerificationStore(beans.getBeanProvider(StringRedisTemplate.class), properties);
    }

    @Test
    void shouldStoreAllFieldsAndPreserveTtlAndConsumption() {
        Fixture fixture = fixture();
        assertThat(issue(fixture).result()).isEqualTo(ISSUED);
        assertThat(redis.opsForHash().entries(key("challenge", fixture.email())))
                .containsEntry("digest", fixture.digest())
                .containsEntry("attempts", "0")
                .containsEntry("maxAttempts", "5");
        assertThat(redis.getExpire(key("challenge", fixture.email()))).isBetween(290L, 300L);
        assertThat(redis.getExpire(key("cooldown", fixture.email()))).isBetween(50L, 60L);
        assertThat(redis.getExpire(key("rate:email", fixture.email()))).isBetween(3590L, 3600L);
        assertThat(redis.getExpire(key("rate:ip", fixture.ip()))).isBetween(3590L, 3600L);
        assertThat(store.verify(fixture.email(), fixture.digest())).isEqualTo(VALID);
        store.consume(fixture.email(), "different-synthetic-digest");
        assertThat(store.verify(fixture.email(), fixture.digest())).isEqualTo(VALID);
        store.consume(fixture.email(), fixture.digest());
        assertThat(store.verify(fixture.email(), fixture.digest())).isEqualTo(EXPIRED);
        assertThat(issue(fixture).result()).isEqualTo(RESEND_TOO_SOON);
    }

    @Test
    void shouldRejectAfterFiveIncorrectAttempts() {
        Fixture fixture = fixture();
        assertThat(issue(fixture).result()).isEqualTo(ISSUED);
        for (int attempt = 1; attempt < 5; attempt++) {
            assertThat(store.verify(fixture.email(), "wrong-synthetic-digest")).isEqualTo(INVALID);
        }
        assertThat(store.verify(fixture.email(), "wrong-synthetic-digest")).isEqualTo(ATTEMPTS_EXHAUSTED);
        assertThat(store.verify(fixture.email(), fixture.digest())).isEqualTo(EXPIRED);
    }

    @Test
    void shouldRejectExpiredChallenge() {
        Fixture fixture = fixture();
        assertThat(issue(fixture).result()).isEqualTo(ISSUED);
        // 只推进本测试的挑战过期，不等待五分钟，也不修改其他键。
        assertThat(redis.expire(key("challenge", fixture.email()), Duration.ZERO)).isTrue();
        assertThat(store.verify(fixture.email(), fixture.digest())).isEqualTo(EXPIRED);
    }

    @Test
    void shouldKeepCooldownAndEmailHourlyBudget() {
        Fixture fixture = fixture();
        assertThat(issue(fixture).result()).isEqualTo(ISSUED);
        assertThat(issue(fixture).result()).isEqualTo(RESEND_TOO_SOON);
        for (int sent = 1; sent < 5; sent++) {
            expireCooldown(fixture);
            assertThat(issue(fixture).result()).isEqualTo(ISSUED);
        }
        expireCooldown(fixture);
        assertThat(issue(fixture).result()).isEqualTo(EMAIL_RATE_LIMITED);
        assertThat(redis.opsForValue().get(key("rate:email", fixture.email()))).isEqualTo("5");
        assertThat(redis.opsForValue().get(key("rate:ip", fixture.ip()))).isEqualTo("5");
    }

    @Test
    void shouldShareIpHourlyBudgetAcrossEmails() {
        String ip = UUID.randomUUID().toString();
        for (int sent = 0; sent < 20; sent++) {
            Fixture fixture = new Fixture(UUID.randomUUID().toString(), ip, "synthetic-digest");
            assertThat(issue(fixture).result()).isEqualTo(ISSUED);
        }
        Fixture rejected = new Fixture(UUID.randomUUID().toString(), ip, "synthetic-digest");
        assertThat(issue(rejected).result()).isEqualTo(IP_RATE_LIMITED);
        assertThat(redis.opsForValue().get(key("rate:ip", ip))).isEqualTo("20");
        assertThat(redis.hasKey(key("challenge", rejected.email()))).isFalse();
    }

    @Test
    void shouldNotCancelReplacementAndShouldRestoreFailedDeliveryBudget() {
        Fixture old = fixture();
        assertThat(issue(old).result()).isEqualTo(ISSUED);
        expireCooldown(old);
        Fixture replacement = new Fixture(old.email(), old.ip(), "replacement-synthetic-digest");
        assertThat(issue(replacement).result()).isEqualTo(ISSUED);
        store.cancelIssue(old.email(), old.ip(), old.digest());
        assertThat(store.verify(replacement.email(), replacement.digest())).isEqualTo(VALID);
        assertThat(redis.opsForValue().get(key("rate:email", old.email()))).isEqualTo("2");
        store.cancelIssue(replacement.email(), replacement.ip(), replacement.digest());
        assertThat(store.verify(replacement.email(), replacement.digest())).isEqualTo(EXPIRED);
        assertThat(redis.opsForValue().get(key("rate:email", old.email()))).isEqualTo("1");
        assertThat(redis.opsForValue().get(key("rate:ip", old.ip()))).isEqualTo("1");
        assertThat(issue(replacement).result()).isEqualTo(ISSUED);
    }

    @Test
    void shouldIssueOnlyOnceForConcurrentRequests() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<EmailVerificationStore.IssueResult>> futures = new ArrayList<>();
            for (int request = 0; request < 8; request++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试并发屏障超时");
                    }
                    return issue(fixture).result();
                }));
            }
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<EmailVerificationStore.IssueResult> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results).containsOnly(ISSUED, RESEND_TOO_SOON);
            assertThat(results.stream().filter(result -> result == ISSUED).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result == RESEND_TOO_SOON).count()).isEqualTo(7);
        }
        assertThat(redis.opsForValue().get(key("rate:email", fixture.email()))).isEqualTo("1");
        assertThat(redis.opsForValue().get(key("rate:ip", fixture.ip()))).isEqualTo("1");
    }

    @Test
    void shouldSendRegistrationCodeAfterRealRedisIssue() throws Exception {
        UserIdentityMapper identities = mock(UserIdentityMapper.class);
        VerificationEmailSender sender = mock(VerificationEmailSender.class);
        AccountRegistrationGateway accounts = mock(AccountRegistrationGateway.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        AuthenticationService authentication = mock(AuthenticationService.class);
        var service = new EmailRegistrationServiceImpl(identities, store, sender, properties, accounts, passwords);
        // 走实际 Controller → Service → Redis；安全过滤器另由既有控制器测试覆盖。
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(service, authentication))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        String email = UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\"}";
        mvc.perform(post("/api/v1/auth/registration-code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.data.verificationCode").doesNotExist());
        mvc.perform(post("/api/v1/auth/registration-code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_RESEND_TOO_SOON"));
        verify(sender, times(1)).send(eq(email), anyString(), eq(Duration.ofMinutes(5)));
        verifyNoInteractions(accounts, passwords, authentication);
    }

    /** 随机合成指纹隔离每项测试，测试数据仅保留于专用 Redis 的短期键。 */
    private Fixture fixture() {
        return new Fixture(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "synthetic-digest");
    }

    private EmailVerificationStore.IssueDecision issue(Fixture fixture) {
        return store.issue(fixture.email(), fixture.ip(), fixture.digest(), properties.policy());
    }

    /** 只推进当前合成指纹的冷却期，小时计数仍保持原有过期规则。 */
    private void expireCooldown(Fixture fixture) {
        assertThat(redis.expire(key("cooldown", fixture.email()), Duration.ZERO)).isTrue();
    }

    private String key(String kind, String fingerprint) {
        return KEY_PREFIX + kind + ":" + fingerprint;
    }

    private record Fixture(String email, String ip, String digest) {
    }
}
