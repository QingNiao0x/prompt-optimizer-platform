package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.domain.UserIdentityKey;
import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 从平台用户表和工作区成员关系构建认证主体。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@Profile("!local-mock")
public class DatabaseUserDetailsService implements UserDetailsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseUserDetailsService.class);

    private final UserAccountMapper accountMapper;
    private final UserIdentityMapper identityMapper;

    public DatabaseUserDetailsService(
            UserAccountMapper accountMapper,
            UserIdentityMapper identityMapper
    ) {
        this.accountMapper = accountMapper;
        this.identityMapper = identityMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserIdentityKey key;
        try {
            String identifier = username == null ? "" : username.trim();
            key = identifier.indexOf('@') >= 0
                    ? UserIdentityKey.email(identifier)
                    : UserIdentityKey.username(identifier);
        } catch (IllegalArgumentException exception) {
            throw notFound("INVALID_LOGIN_IDENTIFIER", "UNKNOWN");
        }
        UserIdentityEntity identity = identityMapper.selectByLoginKey(
                key.type(), key.issuer(), key.normalizedIdentifier());
        if (identity == null) {
            throw notFound("IDENTITY_NOT_FOUND", key.type().name());
        }
        if (identity.getStatus() != UserIdentityStatus.ACTIVE) {
            String reason = identity.getStatus() == UserIdentityStatus.REVOKED
                    ? "IDENTITY_REVOKED"
                    : "IDENTITY_STATUS_INVALID";
            throw notFound(reason, key.type().name());
        }
        UserAccountEntity account = accountMapper.selectById(identity.getUserId());
        if (account == null) {
            throw notFound("ACCOUNT_NOT_FOUND", key.type().name());
        }
        if (account.getPasswordHash() == null || account.getPasswordHash().isBlank()) {
            throw notFound("PASSWORD_HASH_MISSING", key.type().name());
        }
        UUID workspaceId = accountMapper.selectDefaultWorkspaceId(account.getId(), account.getTenantId());
        if (workspaceId == null) {
            throw notFound("DEFAULT_WORKSPACE_NOT_FOUND", key.type().name());
        }
        if (!"ACTIVE".equals(account.getStatus())) {
            LOGGER.warn("账户当前不可登录, requestId={}, 账户状态={}",
                    MDC.get("requestId"), account.getStatus());
        }
        List<SimpleGrantedAuthority> authorities = "PLATFORM_ADMIN".equals(account.getPlatformRole())
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new AuthenticatedUser(
                account.getId(),
                account.getTenantId(),
                workspaceId,
                account.getEmail(),
                identity.getNormalizedIdentifier(),
                account.getDisplayName(),
                account.getPasswordHash(),
                account.getStatus(),
                authorities
        );
    }

    /** 记录不含登录标识的身份加载阶段，随后仍返回统一认证失败以避免账户枚举。 */
    private UsernameNotFoundException notFound(String reason, String identityType) {
        LOGGER.warn("登录身份未通过校验, requestId={}, 身份类型={}, 原因={}",
                MDC.get("requestId"), identityType, reason);
        return new UsernameNotFoundException("用户不存在或尚未配置登录凭据");
    }
}
