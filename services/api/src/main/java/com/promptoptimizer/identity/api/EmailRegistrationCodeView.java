package com.promptoptimizer.identity.api;

/**
 * 验证码发送后的公开时限信息，不返回验证码本身。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record EmailRegistrationCodeView(
        long resendAfterSeconds,
        long expiresInSeconds
) {
}
