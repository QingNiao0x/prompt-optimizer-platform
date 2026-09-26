package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.service.AccountRegistrationGateway;
import com.promptoptimizer.identity.service.RegistrationException;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 通过 MyBatis Mapper 在一个事务内创建个人账户、租户、工作区和邮箱身份。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@Profile("!local-mock")
public class MybatisAccountRegistrationGateway implements AccountRegistrationGateway {

    private final IdentityProvisioningMapper mapper;

    public MybatisAccountRegistrationGateway(ObjectProvider<IdentityProvisioningMapper> mapperProvider) {
        this.mapper = mapperProvider.getIfAvailable();
    }

    @Override
    @Transactional
    public void createPersonalAccount(
            String normalizedEmail,
            String displayName,
            String passwordHash,
            OffsetDateTime verifiedAt
    ) {
        if (mapper == null) {
            throw new RegistrationException(
                    RegistrationException.Reason.SERVICE_UNAVAILABLE,
                    "注册服务暂时不可用，请稍后重试。"
            );
        }

        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        mapper.insertTenant(tenantId, fit(displayName + " 的个人账户", 120));
        mapper.insertUserAccount(userId, tenantId, normalizedEmail, displayName, passwordHash, "USER");
        mapper.insertWorkspace(workspaceId, tenantId, fit(displayName + " 的工作区", 120),
                "注册时自动创建的个人工作区", userId);
        mapper.insertWorkspaceMember(workspaceId, userId, "OWNER");
        mapper.insertUserIdentity(UUID.randomUUID(), userId, "EMAIL", "local", normalizedEmail,
                normalizedEmail, "ACTIVE", verifiedAt);
    }

    private String fit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
