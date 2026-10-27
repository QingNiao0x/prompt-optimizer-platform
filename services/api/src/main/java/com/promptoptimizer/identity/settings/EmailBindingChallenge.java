package com.promptoptimizer.identity.settings;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * email_binding_challenge 的内部映射；归属于单一账户，固定用于邮箱绑定。
 * 目标邮箱、浏览器和验证码仅保存 HMAC；消费后保留防重放状态，不包含业务历史或明文秘密。
 * @author QingNiao
 * @since 0.1.0
 */
public record EmailBindingChallenge(UUID id, UUID userId, String emailFingerprint, String browserFingerprint,
        String codeDigest, String status, int attempts, OffsetDateTime expiresAt) {
    /** 避免异常或测试日志自动打印验证摘要和账户标识。 */
    @Override public String toString() { return "EmailBindingChallenge[redacted]"; }
}
