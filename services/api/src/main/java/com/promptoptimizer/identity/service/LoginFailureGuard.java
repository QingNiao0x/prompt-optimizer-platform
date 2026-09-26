package com.promptoptimizer.identity.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按登录标识和来源地址统计连续失败，达到阈值后锁定一段时间。
 *
 * <p>计数键使用摘要，不保存邮箱、用户名或密码。Redis 不可用且要求 Redis 时拒绝登录，避免多实例各自放行。</p>
 */
@Service
public class LoginFailureGuard {

    private final StringRedisTemplate redis;
    private final boolean requireRedis;
    private final int identifierLimit;
    private final int addressLimit;
    private final Duration window;
    private final Map<String, Counter> memory = new ConcurrentHashMap<>();

    public LoginFailureGuard(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${app.security.login-guard.require-redis:true}") boolean requireRedis,
            @Value("${app.security.login-guard.identifier-limit:5}") int identifierLimit,
            @Value("${app.security.login-guard.address-limit:30}") int addressLimit,
            @Value("${app.security.login-guard.lock-duration:10m}") Duration window
    ) {
        this.redis = redisProvider.getIfAvailable();
        this.requireRedis = requireRedis;
        this.identifierLimit = identifierLimit;
        this.addressLimit = addressLimit;
        this.window = window;
    }

    /** 锁定尚未结束时拒绝本次登录。 */
    public void checkAllowed(String identifier, String clientAddress) {
        requireStore();
        rejectIfLocked(key("id", identifier));
        rejectIfLocked(key("ip", clientAddress));
    }

    /** 记录一次密码失败；达到阈值后进入锁定。 */
    public void recordFailure(String identifier, String clientAddress) {
        requireStore();
        increment(key("id", identifier), identifierLimit);
        increment(key("ip", clientAddress), addressLimit);
    }

    /** 登录成功后清除该标识的失败计数，来源地址计数保留到窗口结束。 */
    public void recordSuccess(String identifier) {
        requireStore();
        String failureKey = failureKey(key("id", identifier));
        if (requireRedis) {
            redis.delete(failureKey);
            return;
        }
        memory.remove(failureKey);
    }

    private void requireStore() {
        if (requireRedis && redis == null) {
            throw new org.springframework.security.authentication.AuthenticationServiceException(
                    "登录失败计数存储不可用");
        }
    }

    private void rejectIfLocked(String subject) {
        String lockKey = lockKey(subject);
        long retryAfter = requireRedis ? redisTtlSeconds(lockKey) : memoryTtlSeconds(lockKey);
        if (retryAfter > 0) {
            throw new LoginGuardException(
                    "LOGIN_LOCKED",
                    "登录失败次数过多，请 " + retryAfter + " 秒后再试。",
                    retryAfter
            );
        }
    }

    private void increment(String subject, int limit) {
        String failureKey = failureKey(subject);
        long count = requireRedis ? redisIncrement(failureKey) : memoryIncrement(failureKey);
        if (count >= limit) {
            if (requireRedis) {
                redis.opsForValue().set(lockKey(subject), "1", window);
                redis.delete(failureKey);
            } else {
                memory.put(lockKey(subject), new Counter(1, System.nanoTime() + window.toNanos()));
                memory.remove(failureKey);
            }
            rejectIfLocked(subject);
        }
    }

    private long redisIncrement(String failureKey) {
        Long count = redis.opsForValue().increment(failureKey);
        if (count != null && count == 1L) {
            redis.expire(failureKey, window);
        }
        return count == null ? 0 : count;
    }

    private long redisTtlSeconds(String lockKey) {
        Long seconds = Boolean.TRUE.equals(redis.hasKey(lockKey)) ? redis.getExpire(lockKey) : 0L;
        if (seconds == null || seconds < 0) {
            return Boolean.TRUE.equals(redis.hasKey(lockKey)) ? window.toSeconds() : 0;
        }
        return seconds;
    }

    private synchronized long memoryIncrement(String failureKey) {
        long now = System.nanoTime();
        Counter current = memory.get(failureKey);
        if (current == null || current.expiresAtNanos <= now) {
            memory.put(failureKey, new Counter(1, now + window.toNanos()));
            return 1;
        }
        Counter next = new Counter(current.count + 1, current.expiresAtNanos);
        memory.put(failureKey, next);
        return next.count;
    }

    private synchronized long memoryTtlSeconds(String lockKey) {
        Counter current = memory.get(lockKey);
        if (current == null) {
            return 0;
        }
        long remaining = current.expiresAtNanos - System.nanoTime();
        if (remaining <= 0) {
            memory.remove(lockKey);
            return 0;
        }
        return Math.max(1, remaining / 1_000_000_000L);
    }

    private String key(String kind, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return kind + ":" + digest(normalized);
    }

    private String failureKey(String subject) {
        return "prompt-optimizer:login:fail:" + subject;
    }

    private String lockKey(String subject) {
        return "prompt-optimizer:login:lock:" + subject;
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record Counter(int count, long expiresAtNanos) {
    }
}
