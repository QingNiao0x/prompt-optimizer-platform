package com.promptoptimizer.identity.infrastructure.security;

import com.promptoptimizer.identity.infrastructure.persistence.UserAccountEntity;
import com.promptoptimizer.identity.infrastructure.persistence.UserAccountRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
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

    public DatabaseUserDetailsService(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = normalizeEmail(username);
        UserAccountEntity account = repository.findByEmailIgnoreCase(email)
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

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private UsernameNotFoundException notFound() {
        return new UsernameNotFoundException("用户不存在或尚未配置登录凭据");
    }
}
