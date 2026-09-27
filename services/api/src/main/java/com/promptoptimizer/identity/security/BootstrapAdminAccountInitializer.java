package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.domain.UserIdentityKey;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 在显式开启且数据库尚未初始化平台管理员时，分别创建邮箱管理员和用户名备用管理员；
 * 已有管理员时只按匹配的活动邮箱身份补齐未占用的配置用户名，不重置密码或角色。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@Profile("!local-mock")
public class BootstrapAdminAccountInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapAdminAccountInitializer.class);
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"
    );

    private final ObjectProvider<IdentityProvisioningMapper> mapperProvider;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String username;
    private final String email;
    private final String displayName;
    private final String password;

    public BootstrapAdminAccountInitializer(
            ObjectProvider<IdentityProvisioningMapper> mapperProvider,
            PasswordEncoder passwordEncoder,
            @Value("${app.security.bootstrap-admin.enabled:false}") boolean enabled,
            @Value("${app.security.bootstrap-admin.username:admin}") String username,
            @Value("${app.security.bootstrap-admin.email:1767443348@qq.com}") String email,
            @Value("${app.security.bootstrap-admin.display-name:Admin}") String displayName,
            @Value("${app.security.bootstrap-admin.password:}") String password
    ) {
        this.mapperProvider = mapperProvider;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.password = password;
    }

    /** 首次初始化在数据库行锁内原子创建两个独立管理员；已消费或已有管理员时不修改账户。 */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            LOGGER.info("event=platform.admin.account_bootstrap.skipped reason=disabled");
            return;
        }

        IdentityProvisioningMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            throw new IllegalStateException("管理员初始化失败：数据库服务不可用");
        }

        Boolean consumed = mapper.selectAdminBootstrapConsumedForUpdate();
        if (Boolean.TRUE.equals(consumed)) {
            linkConfiguredUsernameToExistingAdmin(mapper);
            return;
        }

        if (mapper.countPlatformAdmins() > 0) {
            linkConfiguredUsernameToExistingAdmin(mapper);
            markConsumed(mapper);
            LOGGER.info("event=platform.admin.account_bootstrap.skipped reason=admin_exists");
            return;
        }

        BootstrapIdentity identity = validatedIdentity();
        rejectExistingIdentity(mapper, identity.usernameKey());
        rejectExistingIdentity(mapper, identity.emailKey());

        UUID emailAdminId = createAdminAccount(
                mapper, identity.displayName(), identity.emailKey().normalizedIdentifier(),
                passwordEncoder.encode(identity.password()), "邮箱管理员的个人工作区"
        );
        UUID backupAdminId = createAdminAccount(
                mapper, "备用管理员", null, passwordEncoder.encode(identity.password()),
                "用户名备用管理员的个人工作区"
        );
        insertIdentity(mapper, emailAdminId, identity.emailKey());
        insertIdentity(mapper, backupAdminId, identity.usernameKey());
        markConsumed(mapper);

        LOGGER.info("event=platform.admin.accounts_bootstrapped primaryUserId={} backupUserId={}",
                emailAdminId, backupAdminId);
    }

    /**
     * 为已有管理员补齐启动配置中显式指定的用户名身份；只按活动邮箱身份匹配活动管理员。
     * 已占用或已撤销的用户名不会被覆盖，也不会修改账户密码或角色。
     */
    private void linkConfiguredUsernameToExistingAdmin(IdentityProvisioningMapper mapper) {
        UserIdentityKey usernameKey;
        UserIdentityKey emailKey;
        try {
            usernameKey = UserIdentityKey.username(username);
            if (email == null || email.length() > 320 || !EMAIL.matcher(email.trim()).matches()) {
                throw new IllegalArgumentException("invalid email");
            }
            emailKey = UserIdentityKey.email(email);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("event=platform.admin.username_identity_skipped reason=invalid_bootstrap_identity_config");
            return;
        }

        UUID adminUserId = mapper.selectActivePlatformAdminIdByEmailIdentity(emailKey.normalizedIdentifier());
        if (adminUserId == null) {
            LOGGER.warn("event=platform.admin.username_identity_skipped reason=matching_active_admin_not_found");
            return;
        }
        if (mapper.existsIdentity("USERNAME", UserIdentityKey.LOCAL_ISSUER,
                usernameKey.normalizedIdentifier())) {
            LOGGER.info("event=platform.admin.username_identity_skipped reason=username_identity_already_bound");
            return;
        }

        int inserted = mapper.insertUsernameIdentityIfAbsent(
                UUID.randomUUID(), adminUserId, usernameKey.normalizedIdentifier());
        if (inserted == 1) {
            LOGGER.info("event=platform.admin.username_identity_linked");
        } else {
            LOGGER.warn("event=platform.admin.username_identity_skipped reason=identity_conflict");
        }
    }

    /** 每位管理员使用独立租户和工作区，避免备用账户共享主账户的业务数据作用域。 */
    private UUID createAdminAccount(IdentityProvisioningMapper mapper, String name, String contactEmail,
            String passwordHash, String workspaceDescription) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        mapper.insertTenant(tenantId, fit(name + " 的个人账户", 120));
        mapper.insertUserAccount(userId, tenantId, contactEmail, name, passwordHash, "PLATFORM_ADMIN");
        mapper.insertWorkspace(workspaceId, tenantId, fit(name + " 的工作区", 120), workspaceDescription, userId);
        mapper.insertWorkspaceMember(workspaceId, userId, "OWNER");
        return userId;
    }

    /** 校验启动配置并在内存中构造规范化身份；错误信息不包含配置值或密码。 */
    private BootstrapIdentity validatedIdentity() {
        try {
            UserIdentityKey usernameKey = UserIdentityKey.username(username);
            if (email == null || email.length() > 320 || !EMAIL.matcher(email.trim()).matches()) {
                throw new IllegalArgumentException("invalid email");
            }
            UserIdentityKey emailKey = UserIdentityKey.email(email);
            String normalizedDisplayName = displayName == null ? "" : displayName.trim();
            if (normalizedDisplayName.isBlank() || normalizedDisplayName.length() > 80) {
                throw new IllegalArgumentException("invalid display name");
            }
            if (!com.promptoptimizer.identity.service.impl.PasswordPolicy.meets(password)) {
                throw new IllegalArgumentException("invalid password");
            }
            return new BootstrapIdentity(usernameKey, emailKey, normalizedDisplayName, password);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "管理员初始化配置无效：请检查用户名、邮箱、显示名称及密码长度",
                    exception
            );
        }
    }

    /** 拒绝接管已绑定身份，防止初始化过程覆盖普通用户的登录凭据。 */
    private void rejectExistingIdentity(IdentityProvisioningMapper mapper, UserIdentityKey key) {
        if (mapper.existsIdentity(key.type().name(), key.issuer(), key.normalizedIdentifier())) {
            throw new IllegalStateException("管理员初始化失败：指定的用户名或邮箱已绑定到其他账户");
        }
    }

    /** 插入一条活动身份记录；邮箱与用户名分别绑定到独立账户。 */
    private void insertIdentity(IdentityProvisioningMapper mapper, UUID userId, UserIdentityKey key) {
        mapper.insertUserIdentity(UUID.randomUUID(), userId, key.type().name(), key.issuer(),
                key.normalizedIdentifier(), key.normalizedIdentifier(), "ACTIVE", null);
    }

    /** 持久化一次性标记，确保并发启动和后续重启不会重复创建或重置管理员。 */
    private void markConsumed(IdentityProvisioningMapper mapper) {
        mapper.markAdminBootstrapConsumed();
    }

    private String fit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record BootstrapIdentity(
            UserIdentityKey usernameKey,
            UserIdentityKey emailKey,
            String displayName,
            String password
    ) {
    }
}
