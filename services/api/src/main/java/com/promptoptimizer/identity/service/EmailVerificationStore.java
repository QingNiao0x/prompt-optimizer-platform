package com.promptoptimizer.identity.service;

/**
 * 邮箱注册验证码及限流状态存储。
 *
 * <p>调用方只传邮箱和 IP 的不可逆指纹，存储键中不出现明文个人信息。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface EmailVerificationStore {

    /** 根据邮箱与 IP 指纹执行限流检查，并保存一次性验证码摘要。 */
    IssueDecision issue(
            String emailFingerprint,
            String ipFingerprint,
            String codeDigest,
            EmailVerificationPolicy policy
    );

    /** 校验验证码摘要及有效期，不因校验成功而自动消费验证码。 */
    VerificationResult verify(String emailFingerprint, String codeDigest);

    /** 注册成功后消费验证码，阻止同一验证码再次用于注册。 */
    void consume(String emailFingerprint, String codeDigest);

    /** 邮件投递失败时撤销本次发码占用，允许用户重试。 */
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
