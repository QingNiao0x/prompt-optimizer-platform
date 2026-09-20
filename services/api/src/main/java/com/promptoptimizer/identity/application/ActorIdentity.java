package com.promptoptimizer.identity.application;

import java.util.Objects;
import java.util.Locale;
import java.util.UUID;

/**
 * 当前已认证主体的稳定身份和默认数据作用域。
 *
 * <p>这些标识只能由服务端认证适配器创建，业务请求不得直接提交或覆盖。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ActorIdentity(
        UUID userId,
        UUID tenantId,
        UUID workspaceId,
        String email,
        String displayName
) {

    public ActorIdentity {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        email = Objects.requireNonNull(email, "email must not be null").trim().toLowerCase(Locale.ROOT);
        displayName = Objects.requireNonNull(displayName, "displayName must not be null").trim();
    }
}
