package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 每次管理写操作重新检查数据库角色，避免会话中旧的授权在撤权后继续生效。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class PlatformAdminAccess {

    private final CurrentActor currentActor;
    private final UserAccountMapper users;

    public PlatformAdminAccess(CurrentActor currentActor, ObjectProvider<UserAccountMapper> users) {
        this.currentActor = currentActor;
        this.users = users.getIfAvailable();
    }

    /** 只允许仍处于启用状态的平台管理员访问全平台模型目录。 */
    public void require() {
        var actor = currentActor.require();
        var account = users == null ? null : users.selectById(actor.userId());
        boolean allowed = account != null
                && "ACTIVE".equals(account.getStatus())
                && "PLATFORM_ADMIN".equals(account.getPlatformRole());
        if (!allowed) {
            throw new AccessDeniedException("当前用户无平台模型管理权限");
        }
    }
}
