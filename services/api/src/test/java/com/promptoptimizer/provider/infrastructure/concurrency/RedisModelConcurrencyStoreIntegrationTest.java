package com.promptoptimizer.provider.infrastructure.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 显式开启的真实 Redis 验证；仅使用本轮随机命名的键，不清空或扫描既有业务数据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@EnabledIfSystemProperty(named = "modelConcurrency.redisIntegration", matches = "true")
class RedisModelConcurrencyStoreIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private ModelConcurrencyProperties properties;
    private final List<String> ownedKeys = new ArrayList<>();

    @BeforeEach
    void connectToExplicitTestRedis() {
        connectionFactory = new LettuceConnectionFactory(
                System.getProperty("modelConcurrency.redisHost", "127.0.0.1"),
                Integer.getInteger("modelConcurrency.redisPort", 6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        properties = new ModelConcurrencyProperties();
        properties.setKeyPrefix("prompt-optimizer:test:concurrency:" + UUID.randomUUID());
        ownedKeys.clear();
        ownedKeys.add(properties.getKeyPrefix() + ":global");
    }

    @AfterEach
    void deleteOnlyThisRunsKeys() {
        try {
            if (redis != null) redis.delete(ownedKeys);
        } finally {
            if (connectionFactory != null) connectionFactory.destroy();
        }
    }

    @Test
    void sharesExactlyOneHundredPermitsAcrossTwoIndependentApiInstancesUnderContention() throws Exception {
        try (var first = limiter(); var second = limiter(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ModelConcurrencyLimiter.Permit>> tasks = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                var node = i % 2 == 0 ? first : second;
                tasks.add(workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        return node.acquireGlobal();
                    } catch (ModelConcurrencyException exception) {
                        assertThat(exception.getReason()).isEqualTo(ModelConcurrencyException.Reason.GLOBAL_LIMIT);
                        return null;
                    }
                }));
            }
            start.countDown();
            List<ModelConcurrencyLimiter.Permit> accepted = new ArrayList<>();
            try {
                for (var task : tasks) {
                    var permit = task.get(10, TimeUnit.SECONDS);
                    if (permit != null) accepted.add(permit);
                }
                assertThat(accepted).hasSize(100);
                assertThat(redis.opsForZSet().zCard(ownedKeys.getFirst())).isEqualTo(100);
            } finally {
                accepted.forEach(ModelConcurrencyLimiter.Permit::close);
            }
            assertThat(redis.hasKey(ownedKeys.getFirst())).isFalse();
            try (var next = second.acquireGlobal()) {
                assertThat(next).isNotNull();
            }
        }
    }

    @Test
    void sharesAccountLimitAcrossInstancesAndDoesNotReleaseAnotherRequestsPermit() {
        UUID account = UUID.randomUUID();
        ownedKeys.add(properties.getKeyPrefix() + ":user:" + account);
        try (var first = limiter(); var second = limiter();
             var one = first.acquireUser(account); var two = second.acquireUser(account);
             var three = first.acquireUser(account)) {
            assertThatThrownBy(() -> second.acquireUser(account)).isInstanceOfSatisfying(ModelConcurrencyException.class,
                    failure -> assertThat(failure.getReason()).isEqualTo(ModelConcurrencyException.Reason.USER_LIMIT));
            two.close();
            try (var replacement = second.acquireUser(account)) {
                two.close();
                assertThatThrownBy(() -> first.acquireUser(account)).isInstanceOf(ModelConcurrencyException.class);
            }
        }
        assertThat(redis.hasKey(ownedKeys.getLast())).isFalse();
    }

    @Test
    void keepsLongRunningCallsOccupiedByRenewingTheirLease() {
        properties.setLeaseDuration(Duration.ofSeconds(1));
        properties.setHeartbeatInterval(Duration.ofMillis(100));
        properties.setGlobalLimit(1);
        try (var first = limiter(); var second = limiter(); var running = first.acquireGlobal()) {
            await().pollInterval(Duration.ofMillis(50)).during(Duration.ofMillis(1600))
                    .atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                            assertThatThrownBy(second::acquireGlobal).isInstanceOf(ModelConcurrencyException.class));
        }
        assertThat(redis.hasKey(ownedKeys.getFirst())).isFalse();
    }

    @Test
    void reclaimsAbandonedLeaseWithoutRevivingItOrDeletingItsReplacement() {
        var store = new RedisModelConcurrencyStore(redis);
        String key = ownedKeys.getFirst();
        assertThat(store.acquire(key, "abandoned", 1, Duration.ofMillis(100))).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        assertThat(store.acquire(key, "replacement", 1, Duration.ofSeconds(5))).isTrue();
        assertThat(store.renew(key, "abandoned", Duration.ofSeconds(5))).isFalse();
        store.release(key, "abandoned");
        assertThat(store.acquire(key, "third", 1, Duration.ofSeconds(5))).isFalse();
        store.release(key, "replacement");
        assertThat(redis.hasKey(key)).isFalse();
    }

    private ModelConcurrencyLimiter limiter() {
        return new ModelConcurrencyLimiter(new RedisModelConcurrencyStore(redis), properties);
    }
}
