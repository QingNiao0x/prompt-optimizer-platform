package com.promptoptimizer.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * 显式专用Redis端口56411运行真实Lua，不能连接业务6379；不清库、不创建账号、不调用模型。
 * 测试用Redis应独立启动且不持久化，结束后由启动者停止该专用进程。
 * @author QingNiao
 * @since 0.1.0
 */
@EnabledIfSystemProperty(named = "trial.redisIntegration", matches = "true")
class TrialStoreRedisIntegrationTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private TrialStore first;
    private TrialStore second;
    @BeforeEach void connect() {
        connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 56411),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3)).shutdownTimeout(Duration.ZERO).build());
        connection.afterPropertiesSet(); connection.start(); redis = new StringRedisTemplate(connection);
        try (var conn = connection.getConnection()) { assertThat(conn.ping()).isEqualTo("PONG"); }
        var beans = new StaticListableBeanFactory(); beans.addBean("redis", redis);
        var json = new ObjectMapper().findAndRegisterModules();
        first = new TrialStore(beans.getBeanProvider(StringRedisTemplate.class), json, "REDIS", "openai-compatible");
        second = new TrialStore(beans.getBeanProvider(StringRedisTemplate.class), json, "REDIS", "openai-compatible");
    }
    @AfterEach void close() { if (connection != null) connection.destroy(); }
    @Test void realLuaAllowsOnlyOneImageAttemptAcrossIndependentInstances() throws Exception {
        String guest = UUID.randomUUID().toString(), source = UUID.randomUUID().toString(); first.create(guest, source);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 24; i++) {
                TrialStore node = i % 2 == 0 ? first : second;
                tasks.add(workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try { return node.reserve(guest, source, TrialFeature.IMAGE_PROMPT, UUID.randomUUID(), "fixture"); }
                    catch (TrialException failure) { assertThat(failure.code()).isEqualTo("TRIAL_QUOTA_EXHAUSTED"); return false; }
                }));
            }
            start.countDown(); int accepted = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1); assertThat(second.used(guest, TrialFeature.IMAGE_PROMPT)).isEqualTo(1);
            assertThat(redis.getExpire("prompt-optimizer:trial:visitor:" + guest)).isEqualTo(-1);
        }
    }
    @Test void realLuaKeepsReplayLedgerAfterResultExpiryAndPreservesOtherFeatureTotals() {
        String guest = UUID.randomUUID().toString(), source = UUID.randomUUID().toString(); first.create(guest, source);
        UUID id = UUID.randomUUID();
        assertThat(first.reserve(guest, source, TrialFeature.TEXT_POLISH, id, "fixture")).isTrue();
        assertThat(second.reserve(guest, source, TrialFeature.TEXT_POLISH, id, "fixture")).isFalse();
        var finished = new TrialStore.Snapshot("COMPLETED", "fixture", "\"body\"", Instant.now(), Instant.now().plusSeconds(86400));
        first.finish(guest, TrialFeature.TEXT_POLISH, id, finished);
        assertThat(second.get(guest, TrialFeature.TEXT_POLISH, id).resultJson()).isEqualTo("\"body\"");
        // 只删除本测试自己创建的明确快照键，模拟短期正文到期，不扫描或清空Redis。
        redis.delete("prompt-optimizer:trial:snapshot:" + guest + ":op:textPolish:" + id);
        assertThat(second.get(guest, TrialFeature.TEXT_POLISH, id)).isNull();
        assertThat(second.reserve(guest, source, TrialFeature.TEXT_POLISH, id, "fixture")).isFalse();
        assertThat(second.used(guest, TrialFeature.TEXT_POLISH)).isEqualTo(1);
        assertThat(second.used(guest, TrialFeature.ENHANCEMENT)).isZero();
        assertThat(second.reserve(guest, source, TrialFeature.ENHANCEMENT, UUID.randomUUID(), "other")).isTrue();
    }
    @Test void uploadAfterLedgerLossCannotRecreateIdentityOrRestoreAllowance() {
        String guest = UUID.randomUUID().toString(), source = UUID.randomUUID().toString(); first.create(guest, source);
        assertThat(first.exists(guest)).isTrue();
        first.claimUpload(guest);
        redis.delete("prompt-optimizer:trial:visitor:" + guest);
        assertThatThrownBy(() -> second.claimUpload(guest)).isInstanceOfSatisfying(TrialException.class,
                failure -> assertThat(failure.code()).isEqualTo("TRIAL_SESSION_REQUIRED"));
        assertThat(second.exists(guest)).isFalse();
        assertThat(redis.hasKey("prompt-optimizer:trial:visitor:" + guest)).isFalse();
    }
}
