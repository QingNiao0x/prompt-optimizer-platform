package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.service.ActorIdentity;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Spring Security 使用的认证主体；业务模块通过 CurrentActor 获取安全投影。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class AuthenticatedUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final UUID tenantId;
    private final UUID workspaceId;
    private final String email;
    private final String loginIdentifier;
    private final String displayName;
    private final String status;
    private final List<GrantedAuthority> authorities;
    private transient String passwordHash;
    private final UUID identityId;

    public AuthenticatedUser(
            UUID userId,
            UUID tenantId,
            UUID workspaceId,
            String email,
            String displayName,
            String passwordHash,
            String status,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this(userId, tenantId, workspaceId, email, email, displayName, passwordHash, status, authorities, null);
    }

    /** 允许仅绑定用户名的账户登录；联系邮箱为空时仍保留真实的登录标识。 */
    public AuthenticatedUser(
            UUID userId,
            UUID tenantId,
            UUID workspaceId,
            String email,
            String loginIdentifier,
            String displayName,
            String passwordHash,
            String status,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this(userId, tenantId, workspaceId, email, loginIdentifier, displayName, passwordHash, status, authorities, null);
    }

    /** 保留实际认证身份ID，敏感绑定操作需重新检查该身份未被撤销。 */
    public AuthenticatedUser(UUID userId, UUID tenantId, UUID workspaceId, String email, String loginIdentifier,
            String displayName, String passwordHash, String status, Collection<? extends GrantedAuthority> authorities,
            UUID identityId) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        this.loginIdentifier = Objects.requireNonNull(loginIdentifier, "loginIdentifier must not be null")
                .trim().toLowerCase(Locale.ROOT);
        if (this.loginIdentifier.isBlank()) {
            throw new IllegalArgumentException("loginIdentifier must not be blank");
        }
        this.displayName = Objects.requireNonNull(displayName, "displayName must not be null").trim();
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.authorities = List.copyOf(authorities);
        this.identityId = identityId;
    }

    /** 仅供服务端重新认证使用，不投影到公开账户资料。 */
    public UUID getIdentityId() { return identityId; }

    /** 向业务层提供不含密码哈希和授权实现细节的认证主体。 */
    public ActorIdentity actorIdentity() {
        return new ActorIdentity(userId, tenantId, workspaceId, email, displayName);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginIdentifier;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !"LOCKED".equals(status);
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return "ACTIVE".equals(status);
    }

    /**
     * 登录校验完成后清除 Session Principal 中不再需要的密码哈希。
     */
    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
