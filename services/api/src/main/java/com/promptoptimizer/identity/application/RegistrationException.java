package com.promptoptimizer.identity.application;

/**
 * 邮箱注册流程中的可预期异常。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class RegistrationException extends RuntimeException {

    private final Reason reason;
    private final long retryAfterSeconds;

    public RegistrationException(Reason reason, String message) {
        this(reason, message, 0);
    }

    public RegistrationException(Reason reason, String message, long retryAfterSeconds) {
        super(message);
        this.reason = reason;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public Reason getReason() {
        return reason;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public enum Reason {
        EMAIL_ALREADY_REGISTERED,
        RESEND_TOO_SOON,
        EMAIL_RATE_LIMITED,
        IP_RATE_LIMITED,
        CODE_INVALID,
        CODE_EXPIRED,
        CODE_ATTEMPTS_EXHAUSTED,
        PASSWORD_INVALID,
        DELIVERY_UNAVAILABLE,
        SERVICE_UNAVAILABLE
    }
}
