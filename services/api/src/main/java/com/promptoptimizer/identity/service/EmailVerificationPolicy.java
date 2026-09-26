package com.promptoptimizer.identity.service;

import java.time.Duration;
import java.util.Objects;

/**
 * 邮箱注册验证码的服务端时限与频率策略。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record EmailVerificationPolicy(
        Duration codeTtl,
        Duration resendInterval,
        int maxAttempts,
        int emailHourlyLimit,
        int ipHourlyLimit
) {
    public EmailVerificationPolicy {
        Objects.requireNonNull(codeTtl, "codeTtl must not be null");
        Objects.requireNonNull(resendInterval, "resendInterval must not be null");
        if (codeTtl.isZero() || codeTtl.isNegative()) {
            throw new IllegalArgumentException("codeTtl must be positive");
        }
        if (resendInterval.isZero() || resendInterval.isNegative()) {
            throw new IllegalArgumentException("resendInterval must be positive");
        }
        if (maxAttempts < 1 || emailHourlyLimit < 1 || ipHourlyLimit < 1) {
            throw new IllegalArgumentException("verification limits must be positive");
        }
    }
}
