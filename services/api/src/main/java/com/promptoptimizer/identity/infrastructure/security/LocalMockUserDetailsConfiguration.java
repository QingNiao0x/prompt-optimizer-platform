package com.promptoptimizer.identity.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 无数据库 local-mock 配置使用的受密码保护固定身份。
 *
 * <p>该适配器只用于显式 local-mock profile，且密码仍必须由环境变量注入。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
@Profile("local-mock")
public class LocalMockUserDetailsConfiguration {

    @Bean
    UserDetailsService localMockUserDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${app.security.local-user.email:demo@local}") String email,
            @Value("${app.security.local-user.password:}") String password,
            @Value("${app.security.local-user.display-name:Local Demo User}") String displayName,
            @Value("${app.demo.user-id}") String userId,
            @Value("${app.demo.tenant-id}") String tenantId,
            @Value("${app.demo.workspace-id}") String workspaceId
    ) {
        if (password == null || password.length() < 12) {
            throw new IllegalStateException(
                    "local-mock 模式必须通过 LOCAL_AUTH_PASSWORD 配置至少 12 位的登录密码"
            );
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        UUID configuredUserId = UUID.fromString(userId.trim());
        UUID configuredTenantId = UUID.fromString(tenantId.trim());
        UUID configuredWorkspaceId = UUID.fromString(workspaceId.trim());
        String passwordHash = passwordEncoder.encode(password);
        return candidate -> {
            if (!normalizedEmail.equals(candidate == null ? "" : candidate.trim().toLowerCase(Locale.ROOT))) {
                throw new UsernameNotFoundException("用户不存在");
            }
            // 每次创建新的 Principal，避免 ProviderManager 清除凭据后影响下一次登录。
            return new AuthenticatedUser(
                    configuredUserId,
                    configuredTenantId,
                    configuredWorkspaceId,
                    normalizedEmail,
                    displayName,
                    passwordHash,
                    "ACTIVE",
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
        };
    }
}
