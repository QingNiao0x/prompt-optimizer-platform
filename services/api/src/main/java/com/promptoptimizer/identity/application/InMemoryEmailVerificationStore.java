package com.promptoptimizer.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 单实例开发与单元测试使用的验证码存储。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class InMemoryEmailVerificationStore implements EmailVerificationStore {

    private static final Duration RATE_WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Map<String, Challenge> challenges = new HashMap<>();
    private final Map<String, Instant> cooldowns = new HashMap<>();
    private final Map<String, WindowCounter> emailCounters = new HashMap<>();
    private final Map<String, WindowCounter> ipCounters = new HashMap<>();

    public InMemoryEmailVerificationStore() {
        this(Clock.systemUTC());
    }

    public InMemoryEmailVerificationStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized IssueDecision issue(
            String emailFingerprint,
            String ipFingerprint,
            String codeDigest,
            EmailVerificationPolicy policy
    ) {
        Instant now = clock.instant();
        Instant cooldownUntil = cooldowns.get(emailFingerprint);
        if (cooldownUntil != null && cooldownUntil.isAfter(now)) {
            return new IssueDecision(
                    IssueResult.RESEND_TOO_SOON,
                    remainingSeconds(now, cooldownUntil)
            );
        }

        WindowCounter emailCounter = activeCounter(emailCounters, emailFingerprint, now);
        if (emailCounter.count() >= policy.emailHourlyLimit()) {
            return new IssueDecision(
                    IssueResult.EMAIL_RATE_LIMITED,
                    remainingSeconds(now, emailCounter.expiresAt())
            );
        }
        WindowCounter ipCounter = activeCounter(ipCounters, ipFingerprint, now);
        if (ipCounter.count() >= policy.ipHourlyLimit()) {
            return new IssueDecision(
                    IssueResult.IP_RATE_LIMITED,
                    remainingSeconds(now, ipCounter.expiresAt())
            );
        }

        challenges.put(emailFingerprint, new Challenge(
                codeDigest,
                0,
                policy.maxAttempts(),
                now.plus(policy.codeTtl())
        ));
        cooldowns.put(emailFingerprint, now.plus(policy.resendInterval()));
        emailCounters.put(emailFingerprint, emailCounter.incremented());
        ipCounters.put(ipFingerprint, ipCounter.incremented());
        return new IssueDecision(IssueResult.ISSUED, policy.resendInterval().toSeconds());
    }

    @Override
    public synchronized VerificationResult verify(String emailFingerprint, String codeDigest) {
        Challenge challenge = challenges.get(emailFingerprint);
        Instant now = clock.instant();
        if (challenge == null || !challenge.expiresAt().isAfter(now)) {
            challenges.remove(emailFingerprint);
            return VerificationResult.EXPIRED;
        }
        if (secureEquals(challenge.codeDigest(), codeDigest)) {
            return VerificationResult.VALID;
        }
        int attempts = challenge.attempts() + 1;
        if (attempts >= challenge.maxAttempts()) {
            challenges.remove(emailFingerprint);
            return VerificationResult.ATTEMPTS_EXHAUSTED;
        }
        challenges.put(emailFingerprint, challenge.withAttempts(attempts));
        return VerificationResult.INVALID;
    }

    @Override
    public synchronized void consume(String emailFingerprint, String codeDigest) {
        Challenge challenge = challenges.get(emailFingerprint);
        if (challenge != null && secureEquals(challenge.codeDigest(), codeDigest)) {
            challenges.remove(emailFingerprint);
        }
    }

    @Override
    public synchronized void cancelIssue(
            String emailFingerprint,
            String ipFingerprint,
            String codeDigest
    ) {
        Challenge challenge = challenges.get(emailFingerprint);
        if (challenge == null || !secureEquals(challenge.codeDigest(), codeDigest)) {
            return;
        }
        challenges.remove(emailFingerprint);
        cooldowns.remove(emailFingerprint);
        decrement(emailCounters, emailFingerprint);
        decrement(ipCounters, ipFingerprint);
    }

    private WindowCounter activeCounter(Map<String, WindowCounter> counters, String key, Instant now) {
        WindowCounter current = counters.get(key);
        if (current == null || !current.expiresAt().isAfter(now)) {
            WindowCounter reset = new WindowCounter(0, now.plus(RATE_WINDOW));
            counters.put(key, reset);
            return reset;
        }
        return current;
    }

    private void decrement(Map<String, WindowCounter> counters, String key) {
        WindowCounter current = counters.get(key);
        if (current == null) {
            return;
        }
        if (current.count() <= 1) {
            counters.remove(key);
        } else {
            counters.put(key, new WindowCounter(current.count() - 1, current.expiresAt()));
        }
    }

    private long remainingSeconds(Instant now, Instant expiresAt) {
        return Math.max(1, Duration.between(now, expiresAt).toSeconds());
    }

    /** 以固定时间字节比较验证码摘要，避免普通字符串比较产生明显的时序差异。 */
    private boolean secureEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private record Challenge(String codeDigest, int attempts, int maxAttempts, Instant expiresAt) {
        private Challenge withAttempts(int value) {
            return new Challenge(codeDigest, value, maxAttempts, expiresAt);
        }
    }

    private record WindowCounter(int count, Instant expiresAt) {
        private WindowCounter incremented() {
            return new WindowCounter(count + 1, expiresAt);
        }
    }
}
