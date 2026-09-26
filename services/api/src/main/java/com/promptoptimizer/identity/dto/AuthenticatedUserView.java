package com.promptoptimizer.identity.dto;

import com.promptoptimizer.identity.service.ActorIdentity;

import java.util.UUID;

/**
 * 当前用户的安全前端投影，不包含密码哈希或 Session 标识。
 * 仅绑定用户名的账户没有联系邮箱，email 返回空字符串。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AuthenticatedUserView(
        UUID userId,
        UUID tenantId,
        UUID workspaceId,
        String email,
        String displayName,
        boolean platformAdmin
) {

    /** 保持原有五字段调用方的源码与二进制兼容。 */
    public AuthenticatedUserView(UUID userId, UUID tenantId, UUID workspaceId,
            String email, String displayName) {
        this(userId, tenantId, workspaceId, email, displayName, false);
    }

    /** 只暴露当前认证主体的公开身份与工作区标识。 */
    public static AuthenticatedUserView from(ActorIdentity actor) {
        return from(actor, false);
    }

    /** 管理入口展示只使用安全投影，授权仍由后端逐次校验。 */
    public static AuthenticatedUserView from(ActorIdentity actor, boolean platformAdmin) {
        return new AuthenticatedUserView(
                actor.userId(),
                actor.tenantId(),
                actor.workspaceId(),
                actor.email(),
                actor.displayName(),
                platformAdmin
        );
    }
}
