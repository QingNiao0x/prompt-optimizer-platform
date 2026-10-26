package com.promptoptimizer.identity.service;

import com.promptoptimizer.identity.domain.UserDisplayNames;
import com.promptoptimizer.identity.domain.UserIdentityKey;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 微信资料同步扩展点；未来OAuth回调完成服务端身份核验后调用，当前没有公开HTTP入口或网络授权实现。
 * 昵称写入该微信身份关联的账户资料，保留注册时自填的display_name，不创建身份或合并账户。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@Profile("!local-mock")
public class WechatNicknameSynchronizer {
    private final UserIdentityMapper identities;

    public WechatNicknameSynchronizer(UserIdentityMapper identities) { this.identities = identities; }

    /**
     * 应用标识、主体和昵称必须来自服务端已核验的微信资料，不能直接传入浏览器声明的第三方身份。
     * 返回false表示无有效昵称或没有活动的绑定关系；不会把撤销、锁定或禁用账户恢复为可用。
     */
    @Transactional
    public boolean syncVerifiedNickname(String appId, String subject, String nickname) {
        String normalized = UserDisplayNames.optional(nickname);
        if (normalized == null) return false;
        normalized = UserDisplayNames.resolve(normalized, null);
        UserIdentityKey key = UserIdentityKey.wechat(appId, subject);
        // 微信昵称和平台自填昵称来源不同，仅更新关联身份资料；昵称永远不能用于推断账号所有权。
        return identities.syncWechatNickname(key.issuer(), key.normalizedIdentifier(), normalized,
                OffsetDateTime.now(ZoneOffset.UTC).toString()) == 1;
    }
}
