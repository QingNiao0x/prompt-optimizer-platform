package com.promptoptimizer.identity.infrastructure.security;

import com.promptoptimizer.identity.domain.UserIdentityKey;
import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.infrastructure.persistence.UserAccountEntity;
import com.promptoptimizer.identity.infrastructure.persistence.UserAccountRepository;
import com.promptoptimizer.identity.infrastructure.persistence.UserIdentityEntity;
import com.promptoptimizer.identity.infrastructure.persistence.UserIdentityRepository;
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

    private final UserAccountRepository repository;
    private final UserIdentityRepository identityRepository;

    public DatabaseUserDetailsService(
            UserAccountRepository repository,
            UserIdentityRepository identityRepository
    ) {
        this.repository = repository;
        this.identityRepository = identityRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserIdentityKey key;
        try {
            key = UserIdentityKey.email(username);
        } catch (IllegalArgumentException exception) {
            throw notFound();
        }
        UserIdentityEntity identity = identityRepository
                .findByIdentityTypeAndIssuerAndNormalizedIdentifierAndStatus(
                        key.type(),
                        key.issuer(),
                        key.normalizedIdentifier(),
                        UserIdentityStatus.ACTIVE
                )
                .orElseThrow(this::notFound);
        UserAccountEntity account = repository.findById(identity.getUserId())
                .orElseThrow(this::notFound);
        if (account.getPasswordHash() == null || account.getPasswordHash().isBlank()) {
            throw notFound();
        }
        UUID workspaceId = repository.findDefaultWorkspaceId(account.getId(), account.getTenantId())
                .orElseThrow(this::notFound);
        return new AuthenticatedUser(
                account.getId(),
                account.getTenantId(),
                workspaceId,
                account.getEmail(),
                account.getDisplayName(),
                account.getPasswordHash(),
                account.getStatus(),
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    private UsernameNotFoundException notFound() {
        return new UsernameNotFoundException("用户不存在或尚未配置登录凭据");
    }
}
