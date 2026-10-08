package com.promptoptimizer.identity.entity;

import com.promptoptimizer.identity.domain.SmsPurpose;
import com.promptoptimizer.identity.domain.SmsChallengeState;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * sms_verification_challenge 持久化投影；由用途、号码/浏览器HMAC及可空绑定操作者隔离。
 * id 为一次性操作主键；结果账户引用 user_account；不含验证码、明文号码、密码或JSON。
 * 短时安全记录不提供业务删除接口，过期不代表可再次消费。
 * @author QingNiao
 * @since 0.1.0
 */
public record SmsChallenge(UUID id, SmsPurpose purpose, String phoneFingerprint, String browserFingerprint,
        UUID actorUserId, String schemeName, SmsChallengeState state, int attempts, UUID verificationToken,
        OffsetDateTime expiresAt, UUID resultUserId, String resultCode, OffsetDateTime createdAt) {
    /** 摘要和绑定信息也不参与诊断输出。 */
    @Override public String toString() { return "SmsChallenge[redacted]"; }
}
