package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.application.EmailVerificationPolicy;
import com.promptoptimizer.identity.application.EmailVerificationStore;
import com.promptoptimizer.identity.application.InMemoryEmailVerificationStore;
import com.promptoptimizer.identity.application.RegistrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 原子限流存储；仅在明确允许时降级为进程内存。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class HybridEmailVerificationStore implements EmailVerificationStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(HybridEmailVerificationStore.class);
    private static final String KEY_PREFIX = "prompt-optimizer:email-registration:v1:";

    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
            local emailCount = tonumber(redis.call('GET', KEYS[3]) or '0')
            if emailCount >= tonumber(ARGV[5]) then return 3 end
            local ipCount = tonumber(redis.call('GET', KEYS[4]) or '0')
            if ipCount >= tonumber(ARGV[6]) then return 4 end
            redis.call('HSET', KEYS[1], 'digest', ARGV[1], 'attempts', '0', 'maxAttempts', ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
            emailCount = redis.call('INCR', KEYS[3])
            if emailCount == 1 then redis.call('EXPIRE', KEYS[3], 3600) end
            ipCount = redis.call('INCR', KEYS[4])
            if ipCount == 1 then redis.call('EXPIRE', KEYS[4], 3600) end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            local stored = redis.call('HGET', KEYS[1], 'digest')
            if not stored then return 2 end
            if stored == ARGV[1] then return 1 end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            local maxAttempts = tonumber(redis.call('HGET', KEYS[1], 'maxAttempts'))
            if attempts >= maxAttempts then
                redis.call('DEL', KEYS[1])
                return 4
            end
            return 3
            """, Long.class);

    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local stored = redis.call('HGET', KEYS[1], 'digest')
            if stored and stored == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> CANCEL_SCRIPT = new DefaultRedisScript<>("""
            local stored = redis.call('HGET', KEYS[1], 'digest')
            if not stored or stored ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('DEL', KEYS[2])
            local emailCount = tonumber(redis.call('GET', KEYS[3]) or '0')
            if emailCount <= 1 then redis.call('DEL', KEYS[3]) else redis.call('DECR', KEYS[3]) end
            local ipCount = tonumber(redis.call('GET', KEYS[4]) or '0')
            if ipCount <= 1 then redis.call('DEL', KEYS[4]) else redis.call('DECR', KEYS[4]) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RegistrationProperties properties;
    private final InMemoryEmailVerificationStore fallback;

    public HybridEmailVerificationStore(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            RegistrationProperties properties
    ) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.properties = properties;
        this.fallback = new InMemoryEmailVerificationStore();
    }

    @Override
    public IssueDecision issue(
            String emailFingerprint,
            String ipFingerprint,
            String codeDigest,
            EmailVerificationPolicy policy
    ) {
        if (usesLocalMemoryStore()) {
            return fallback.issue(emailFingerprint, ipFingerprint, codeDigest, policy);
        }
        if (redisTemplate == null) {
            return fallbackOrFail(() -> fallback.issue(
                    emailFingerprint,
                    ipFingerprint,
                    codeDigest,
                    policy
            ));
        }
        try {
            Long result = redisTemplate.execute(
                    ISSUE_SCRIPT,
                    keys(emailFingerprint, ipFingerprint),
                    codeDigest,
                    seconds(policy.codeTtl()),
                    seconds(policy.resendInterval()),
                    Integer.toString(policy.maxAttempts()),
                    Integer.toString(policy.emailHourlyLimit()),
                    Integer.toString(policy.ipHourlyLimit())
            );
            return mapIssueResult(result, policy);
        } catch (RuntimeException exception) {
            logRedisFailure(exception);
            return fallbackOrFail(() -> fallback.issue(
                    emailFingerprint,
                    ipFingerprint,
                    codeDigest,
                    policy
            ));
        }
    }

    @Override
    public VerificationResult verify(String emailFingerprint, String codeDigest) {
        if (usesLocalMemoryStore()) {
            return fallback.verify(emailFingerprint, codeDigest);
        }
        if (redisTemplate == null) {
            return fallbackOrFail(() -> fallback.verify(emailFingerprint, codeDigest));
        }
        try {
            Long result = redisTemplate.execute(
                    VERIFY_SCRIPT,
                    List.of(challengeKey(emailFingerprint)),
                    codeDigest
            );
            return switch (result == null ? 0 : result.intValue()) {
                case 1 -> VerificationResult.VALID;
                case 3 -> VerificationResult.INVALID;
                case 4 -> VerificationResult.ATTEMPTS_EXHAUSTED;
                default -> VerificationResult.EXPIRED;
            };
        } catch (RuntimeException exception) {
            logRedisFailure(exception);
            return fallbackOrFail(() -> fallback.verify(emailFingerprint, codeDigest));
        }
    }

    @Override
    public void consume(String emailFingerprint, String codeDigest) {
        if (usesLocalMemoryStore()) {
            fallback.consume(emailFingerprint, codeDigest);
            return;
        }
        if (redisTemplate == null) {
            fallbackOrFail(() -> {
                fallback.consume(emailFingerprint, codeDigest);
                return null;
            });
            return;
        }
        try {
            redisTemplate.execute(
                    CONSUME_SCRIPT,
                    List.of(challengeKey(emailFingerprint)),
                    codeDigest
            );
        } catch (RuntimeException exception) {
            logRedisFailure(exception);
            fallbackOrFail(() -> {
                fallback.consume(emailFingerprint, codeDigest);
                return null;
            });
        }
    }

    @Override
    public void cancelIssue(String emailFingerprint, String ipFingerprint, String codeDigest) {
        if (usesLocalMemoryStore()) {
            fallback.cancelIssue(emailFingerprint, ipFingerprint, codeDigest);
            return;
        }
        if (redisTemplate == null) {
            fallbackOrFail(() -> {
                fallback.cancelIssue(emailFingerprint, ipFingerprint, codeDigest);
                return null;
            });
            return;
        }
        try {
            redisTemplate.execute(
                    CANCEL_SCRIPT,
                    keys(emailFingerprint, ipFingerprint),
                    codeDigest
            );
        } catch (RuntimeException exception) {
            logRedisFailure(exception);
            fallbackOrFail(() -> {
                fallback.cancelIssue(emailFingerprint, ipFingerprint, codeDigest);
                return null;
            });
        }
    }

    private List<String> keys(String emailFingerprint, String ipFingerprint) {
        return List.of(
                challengeKey(emailFingerprint),
                KEY_PREFIX + "cooldown:" + emailFingerprint,
                KEY_PREFIX + "rate:email:" + emailFingerprint,
                KEY_PREFIX + "rate:ip:" + ipFingerprint
        );
    }

    private String challengeKey(String emailFingerprint) {
        return KEY_PREFIX + "challenge:" + emailFingerprint;
    }

    /** 本地日志投递明确允许无 Redis 时，保持整个验证码生命周期在同一内存存储中。 */
    private boolean usesLocalMemoryStore() {
        return !properties.isRequireRedis()
                && "log".equalsIgnoreCase(properties.getDeliveryMode().trim());
    }

    private IssueDecision mapIssueResult(Long result, EmailVerificationPolicy policy) {
        return switch (result == null ? 0 : result.intValue()) {
            case 1 -> new IssueDecision(IssueResult.ISSUED, policy.resendInterval().toSeconds());
            case 2 -> new IssueDecision(IssueResult.RESEND_TOO_SOON, policy.resendInterval().toSeconds());
            case 3 -> new IssueDecision(IssueResult.EMAIL_RATE_LIMITED, 3600);
            case 4 -> new IssueDecision(IssueResult.IP_RATE_LIMITED, 3600);
            default -> throw unavailable();
        };
    }

    private String seconds(java.time.Duration duration) {
        return Long.toString(Math.max(1, duration.toSeconds()));
    }

    /** 多实例强制 Redis 时直接失败；仅在明确允许单机模式时执行本地降级操作。 */
    private <T> T fallbackOrFail(java.util.function.Supplier<T> operation) {
        if (properties.isRequireRedis()) {
            throw unavailable();
        }
        return operation.get();
    }

    /** 日志只记录异常类型，不写入验证码、邮箱或 Redis 键。 */
    private void logRedisFailure(RuntimeException exception) {
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        LOGGER.warn(
                "邮箱验证码 Redis 操作失败；包装异常：{}；根因类型：{}",
                exception.getClass().getSimpleName(),
                rootCause.getClass().getSimpleName()
        );
    }

    private RegistrationException unavailable() {
        return new RegistrationException(
                RegistrationException.Reason.SERVICE_UNAVAILABLE,
                "验证码服务暂时不可用，请稍后重试。"
        );
    }
}
