package com.promptoptimizer.identity.api;

import com.promptoptimizer.identity.application.ActorIdentity;

import java.util.UUID;

/**
 * 当前用户的安全前端投影，不包含密码哈希或 Session 标识。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AuthenticatedUserView(
        UUID userId,
        UUID tenantId,
        UUID workspaceId,
        String email,
        String displayName
) {

    public static AuthenticatedUserView from(ActorIdentity actor) {
        return new AuthenticatedUserView(
                actor.userId(),
                actor.tenantId(),
                actor.workspaceId(),
                actor.email(),
                actor.displayName()
        );
    }
}
