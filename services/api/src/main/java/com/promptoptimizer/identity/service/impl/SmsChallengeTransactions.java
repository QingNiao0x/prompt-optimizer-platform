package com.promptoptimizer.identity.service.impl;

import com.promptoptimizer.identity.domain.SmsPurpose;
import com.promptoptimizer.identity.domain.SmsChallengeState;
import com.promptoptimizer.identity.entity.SmsChallenge;
import com.promptoptimizer.identity.mapper.SmsChallengeMapper;
import com.promptoptimizer.identity.service.SmsException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * 云调用前后的短事务；数据库状态为授权事实来源，不依赖Redis事后消费。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@Profile("!local-mock")
public class SmsChallengeTransactions {
    private final SmsChallengeMapper mapper;
    public SmsChallengeTransactions(SmsChallengeMapper mapper) { this.mapper = mapper; }

    /** 同号码同用途只保留最新未消费挑战，历史结果仍可在原浏览器恢复。 */
    @Transactional
    public SmsChallenge create(SmsPurpose purpose, String phone, String browser, UUID actor, String scheme) {
        mapper.lockSubject(subject(phone, purpose));
        mapper.supersede(phone, purpose);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var challenge = new SmsChallenge(UUID.randomUUID(), purpose, phone, browser, actor, scheme,
                SmsChallengeState.SENDING, 0, null, now.plusMinutes(5), null, null, now);
        mapper.insert(challenge);
        return challenge;
    }

    /** 预留唯一云核验者；已验证或已完成结果不会再次请求云接口。 */
    @Transactional
    public SmsChallenge reserve(UUID id, SmsPurpose purpose, String phone, String browser, UUID actor, UUID token) {
        mapper.lockSubject(subject(phone, purpose));
        SmsChallenge challenge = checked(mapper.find(id), purpose, phone, browser, actor);
        if (challenge.state() == SmsChallengeState.VERIFIED || challenge.state() == SmsChallengeState.CONSUMED) return challenge;
        if (challenge.state() == SmsChallengeState.VERIFYING) {
            throw new SmsException(409, "SMS_VERIFICATION_IN_PROGRESS", "验证码正在核验，请稍后重试。", 2);
        }
        if (challenge.attempts() >= 5) throw new SmsException(400, "SMS_ATTEMPTS_EXHAUSTED", "验证码尝试次数已用尽，请重新获取。");
        if (mapper.reserve(id, token) != 1) throw SmsException.invalid();
        return challenge;
    }

    /** 绑定、用途和有效期均必须匹配；CONSUMED也不能跨浏览器恢复。 */
    public static SmsChallenge checked(SmsChallenge challenge, SmsPurpose purpose, String phone, String browser, UUID actor) {
        if (challenge == null || challenge.purpose() != purpose || !Objects.equals(challenge.phoneFingerprint(), phone)
                || !Objects.equals(challenge.browserFingerprint(), browser) || !Objects.equals(challenge.actorUserId(), actor)
                || !challenge.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) throw SmsException.invalid();
        return challenge;
    }

    /** 固定锁序的主体键只包含HMAC及枚举，不含明文手机号。 */
    public static String subject(String phone, SmsPurpose purpose) { return phone + ":" + purpose.name(); }
}
