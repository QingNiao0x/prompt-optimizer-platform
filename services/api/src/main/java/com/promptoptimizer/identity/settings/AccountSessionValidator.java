package com.promptoptimizer.identity.settings;

import com.promptoptimizer.identity.security.AuthenticatedUser;
import com.promptoptimizer.identity.domain.UserIdentityStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 每请求核对数据库账户、身份、安全版本与平台角色；Redis会话残留不能绕过撤销。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@Profile("!local-mock")
public class AccountSessionValidator {
    private final AccountSettingsMapper mapper;
    public AccountSessionValidator(AccountSettingsMapper mapper) { this.mapper = mapper; }

    /** 仅真实数据库身份可通过，旧版缺失身份或角色已变化的会话需要重新登录。 */
    public boolean valid(AuthenticatedUser principal) {
        return refresh(principal) != null;
    }

    /** 校验成功后刷新最小展示投影，其他设备下一请求即可看到新昵称；权限变化必须重登。 */
    public AuthenticatedUser refresh(AuthenticatedUser principal) {
        var actor = principal.actorIdentity();
        var account = mapper.account(actor.userId(), false);
        if (account == null || account.getDeletedAt() != null || !"ACTIVE".equals(account.getStatus())
                || !actor.tenantId().equals(account.getTenantId()) || account.getSecurityVersion() != principal.getSecurityVersion()) return null;
        boolean sessionAdmin = principal.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
        if (sessionAdmin != "PLATFORM_ADMIN".equals(account.getPlatformRole())) return null;
        var identity = principal.getIdentityId() == null ? null : mapper.identity(principal.getIdentityId());
        if (identity == null || !actor.userId().equals(identity.getUserId()) || identity.getStatus() != UserIdentityStatus.ACTIVE) return null;
        if (java.util.Objects.equals(actor.displayName(), account.getDisplayName())
                && java.util.Objects.equals(actor.email(), account.getEmail() == null ? "" : account.getEmail())) return principal;
        var refreshed = new AuthenticatedUser(actor.userId(), actor.tenantId(), actor.workspaceId(), account.getEmail(),
                principal.getUsername(), account.getDisplayName(), "", "ACTIVE", principal.getAuthorities(),
                principal.getIdentityId(), principal.getSecurityVersion());
        refreshed.eraseCredentials();
        return refreshed;
    }
}
