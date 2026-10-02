package com.promptoptimizer.provider.infrastructure.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理账号请求及实际上游调用的租约；成功、失败都释放，进程退出后的遗留名额按租期回收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class ModelConcurrencyLimiter implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelConcurrencyLimiter.class);
    private final ModelConcurrencyStore store;
    private final ModelConcurrencyProperties properties;
    private final Map<String, Permit> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat;
    private volatile boolean healthy = true;

    /** 创建续租任务；不读取或记录用户正文、邮箱和模型密钥。 */
    ModelConcurrencyLimiter(ModelConcurrencyStore store, ModelConcurrencyProperties properties) {
        this.store = store;
        this.properties = properties;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "model-concurrency-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.getHeartbeatInterval().toMillis();
        heartbeat.scheduleWithFixedDelay(this::renewActive, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** 为实际发送到模型的 HTTP 调用占用平台共享名额，包括所有路由和内部重试。 */
    public Permit acquireGlobal() {
        return acquire("global", properties.getGlobalLimit(), ModelConcurrencyException.Reason.GLOBAL_LIMIT);
    }

    /** 使用服务端账号 ID 计数，同一账号更换工作区、Session 或设备不会增加容量。 */
    public Permit acquireUser(UUID userId) {
        return acquire("user:" + userId, properties.getUserLimit(), ModelConcurrencyException.Reason.USER_LIMIT);
    }

    /** 存储故障时拒绝新增调用，不退回各实例独立计数。 */
    private Permit acquire(String scope, int limit, ModelConcurrencyException.Reason reason) {
        if (!healthy) throw unavailable(null);
        String key = properties.getKeyPrefix() + ":" + scope;
        String token = UUID.randomUUID().toString();
        final boolean acquired;
        try {
            acquired = store.acquire(key, token, limit, properties.getLeaseDuration());
        } catch (RuntimeException exception) {
            LOGGER.error("event=model.concurrency.acquire_failed scope={} causeType={}",
                    reason.name(), exception.getClass().getSimpleName());
            throw unavailable(exception);
        }
        if (!acquired) throw new ModelConcurrencyException(reason, limit, null);
        Permit permit = new Permit(key, token);
        active.put(token, permit);
        return permit;
    }

    /** 执行中的请求持续续租；续租失败期间本实例停止接纳新调用。 */
    private void renewActive() {
        boolean renewed = true;
        for (Permit permit : active.values()) {
            if (permit.closed.get()) continue;
            try {
                if (!store.renew(permit.key, permit.token, properties.getLeaseDuration())
                        && !permit.closed.get()) renewed = false;
            } catch (RuntimeException exception) {
                renewed = false;
                LOGGER.error("event=model.concurrency.renew_failed causeType={}", exception.getClass().getSimpleName());
            }
        }
        if (!renewed && healthy) LOGGER.error("event=model.concurrency.lease_unavailable");
        healthy = renewed;
    }

    /** 生成稳定的公开错误，底层原因只保留在异常链中。 */
    private ModelConcurrencyException unavailable(Throwable cause) {
        return new ModelConcurrencyException(ModelConcurrencyException.Reason.STORE_UNAVAILABLE, 0, cause);
    }

    /** 停止续租；仍在执行的请求自行释放，进程终止时通过 Redis 租期回收。 */
    @Override
    public void close() {
        heartbeat.shutdownNow();
    }

    /** 一次性名额句柄；重复关闭及过期后的关闭都不会释放其他调用的名额。 */
    public final class Permit implements AutoCloseable {
        private final String key;
        private final String token;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(String key, String token) {
            this.key = key;
            this.token = token;
        }

        /** 释放失败不覆盖本次业务结果，遗留令牌由租约清理并记录错误供排查。 */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            active.remove(token);
            try {
                store.release(key, token);
            } catch (RuntimeException exception) {
                LOGGER.error("event=model.concurrency.release_failed causeType={}", exception.getClass().getSimpleName());
            }
        }
    }
}
