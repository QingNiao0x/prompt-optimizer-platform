package com.promptoptimizer.provider.infrastructure.concurrency;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 本地单实例的并发存储；生产 Redis 故障时不得将其作为自动回退。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class InMemoryModelConcurrencyStore implements ModelConcurrencyStore {

    private final Map<String, Map<String, Long>> permits = new HashMap<>();

    @Override
    public synchronized boolean acquire(String key, String token, int limit, Duration leaseDuration) {
        long now = System.nanoTime();
        Map<String, Long> active = permits.computeIfAbsent(key, ignored -> new HashMap<>());
        active.values().removeIf(expiresAt -> expiresAt <= now);
        if (active.size() >= limit) return false;
        active.put(token, now + leaseDuration.toNanos());
        return true;
    }

    @Override
    public synchronized boolean renew(String key, String token, Duration leaseDuration) {
        Map<String, Long> active = permits.get(key);
        long now = System.nanoTime();
        if (active == null || active.getOrDefault(token, 0L) <= now) return false;
        active.put(token, now + leaseDuration.toNanos());
        return true;
    }

    @Override
    public synchronized void release(String key, String token) {
        Map<String, Long> active = permits.get(key);
        if (active == null) return;
        active.remove(token);
        if (active.isEmpty()) permits.remove(key);
    }
}
