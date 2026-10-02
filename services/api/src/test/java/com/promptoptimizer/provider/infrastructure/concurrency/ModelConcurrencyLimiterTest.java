package com.promptoptimizer.provider.infrastructure.concurrency;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证并发竞争、账号隔离、幂等释放和存储失败关闭的行为。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ModelConcurrencyLimiterTest {

    @Test
    void admitsExactlyFiftyOfOneHundredSimultaneousCallsAndReusesReleasedCapacity() throws Exception {
        try (var limiter = new ModelConcurrencyLimiter(new InMemoryModelConcurrencyStore(), new ModelConcurrencyProperties());
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ModelConcurrencyLimiter.Permit>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        return limiter.acquireGlobal();
                    } catch (ModelConcurrencyException exception) {
                        assertThat(exception.getReason()).isEqualTo(ModelConcurrencyException.Reason.GLOBAL_LIMIT);
                        return null;
                    }
                }));
            }
            start.countDown();
            List<ModelConcurrencyLimiter.Permit> admitted = new ArrayList<>();
            try {
                for (var result : results) {
                    var permit = result.get(10, TimeUnit.SECONDS);
                    if (permit != null) admitted.add(permit);
                }
                assertThat(admitted).hasSize(50);
                assertLimit(limiter::acquireGlobal, ModelConcurrencyException.Reason.GLOBAL_LIMIT);
                admitted.getFirst().close();
                admitted.getFirst().close();
                try (var replacement = limiter.acquireGlobal()) {
                    assertLimit(limiter::acquireGlobal, ModelConcurrencyException.Reason.GLOBAL_LIMIT);
                }
            } finally {
                admitted.forEach(ModelConcurrencyLimiter.Permit::close);
            }
            try (var next = limiter.acquireGlobal()) {
                assertThat(next).isNotNull();
            }
        }
    }

    @Test
    void limitsOneAccountToThreeWhileAllowingAnotherAccount() {
        try (var limiter = new ModelConcurrencyLimiter(new InMemoryModelConcurrencyStore(), new ModelConcurrencyProperties())) {
            UUID account = UUID.randomUUID();
            try (var first = limiter.acquireUser(account);
                 var second = limiter.acquireUser(account);
                 var third = limiter.acquireUser(account);
                 var other = limiter.acquireUser(UUID.randomUUID())) {
                assertLimit(() -> limiter.acquireUser(account), ModelConcurrencyException.Reason.USER_LIMIT);
                second.close();
                try (var replacement = limiter.acquireUser(account)) {
                    assertLimit(() -> limiter.acquireUser(account), ModelConcurrencyException.Reason.USER_LIMIT);
                }
            }
        }
    }

    @Test
    void rejectsCallsWhenSharedStoreFailsInsteadOfSwitchingToLocalCounters() {
        ModelConcurrencyStore store = mock(ModelConcurrencyStore.class);
        when(store.acquire(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new RedisConnectionFailureException("test store outage"));
        try (var limiter = new ModelConcurrencyLimiter(store, new ModelConcurrencyProperties())) {
            assertLimit(limiter::acquireGlobal, ModelConcurrencyException.Reason.STORE_UNAVAILABLE);
            assertLimit(() -> limiter.acquireUser(UUID.randomUUID()), ModelConcurrencyException.Reason.STORE_UNAVAILABLE);
        }
    }

    private void assertLimit(Runnable operation, ModelConcurrencyException.Reason reason) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ModelConcurrencyException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(reason));
    }
}
