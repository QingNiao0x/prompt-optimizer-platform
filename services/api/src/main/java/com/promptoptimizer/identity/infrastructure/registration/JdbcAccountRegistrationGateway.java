package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.application.AccountRegistrationGateway;
import com.promptoptimizer.identity.application.RegistrationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 使用参数化 SQL 原子创建一个可立即使用的个人账户。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@Profile("!local-mock")
public class JdbcAccountRegistrationGateway implements AccountRegistrationGateway {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public JdbcAccountRegistrationGateway(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    @Override
    @Transactional
    public void createPersonalAccount(
            String normalizedEmail,
            String displayName,
            String passwordHash,
            OffsetDateTime verifiedAt
    ) {
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            throw new RegistrationException(
                    RegistrationException.Reason.SERVICE_UNAVAILABLE,
                    "注册服务暂时不可用，请稍后重试。"
            );
        }

        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenant (id, name, tenant_type, plan_code, status) "
                        + "VALUES (?, ?, 'PERSONAL', 'FREE', 'ACTIVE')",
                tenantId,
                fit(displayName + " 的个人账户", 120)
        );
        jdbc.update(
                "INSERT INTO user_account (id, tenant_id, email, display_name, password_hash, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                userId,
                tenantId,
                normalizedEmail,
                displayName,
                passwordHash
        );
        jdbc.update(
                "INSERT INTO workspace (id, tenant_id, name, description, created_by, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                workspaceId,
                tenantId,
                fit(displayName + " 的工作区", 120),
                "注册时自动创建的个人工作区",
                userId
        );
        jdbc.update(
                "INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')",
                workspaceId,
                userId
        );
        jdbc.update(
                "INSERT INTO user_identity "
                        + "(id, user_id, identity_type, issuer, identifier, normalized_identifier, "
                        + "status, verified_at) "
                        + "VALUES (?, ?, 'EMAIL', 'local', ?, ?, 'ACTIVE', ?)",
                identityId,
                userId,
                normalizedEmail,
                normalizedEmail,
                verifiedAt
        );
    }

    private String fit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
