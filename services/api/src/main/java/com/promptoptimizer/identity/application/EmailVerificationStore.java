package com.promptoptimizer.identity.application;

/**
 * 邮箱注册验证码及限流状态存储。
 *
 * <p>调用方只传邮箱和 IP 的不可逆指纹，存储键中不出现明文个人信息。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface EmailVerificationStore {

    IssueDecision issue(
            String emailFingerprint,
            String ipFingerprint,
            String codeDigest,
            EmailVerificationPolicy policy
    );

    VerificationResult verify(String emailFingerprint, String codeDigest);

    void consume(String emailFingerprint, String codeDigest);

    void cancelIssue(String emailFingerprint, String ipFingerprint, String codeDigest);

    enum IssueResult {
        ISSUED,
        RESEND_TOO_SOON,
        EMAIL_RATE_LIMITED,
        IP_RATE_LIMITED
    }

    record IssueDecision(IssueResult result, long retryAfterSeconds) {
    }

    enum VerificationResult {
        VALID,
        INVALID,
        EXPIRED,
        ATTEMPTS_EXHAUSTED
    }
}
