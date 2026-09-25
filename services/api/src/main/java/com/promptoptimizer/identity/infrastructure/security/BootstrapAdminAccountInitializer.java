package com.promptoptimizer.identity.infrastructure.security;

import com.promptoptimizer.identity.domain.UserIdentityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 在显式开启且数据库尚未初始化平台管理员时，分别创建邮箱管理员和用户名备用管理员。
 * 初始密码只从受保护的运行环境读取，初始化标记阻止后续重启覆盖密码或重复建号。
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

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String username;
    private final String email;
    private final String displayName;
    private final String password;

    public BootstrapAdminAccountInitializer(
            ObjectProvider<JdbcTemplate> jdbcProvider,
            PasswordEncoder passwordEncoder,
            @Value("${app.security.bootstrap-admin.enabled:false}") boolean enabled,
            @Value("${app.security.bootstrap-admin.username:admin}") String username,
            @Value("${app.security.bootstrap-admin.email:1767443348@qq.com}") String email,
            @Value("${app.security.bootstrap-admin.display-name:Admin}") String displayName,
            @Value("${app.security.bootstrap-admin.password:}") String password
    ) {
        this.jdbcProvider = jdbcProvider;
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
            return;
        }

        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException("管理员初始化失败：数据库服务不可用");
        }

        Boolean consumed = jdbc.queryForObject(
                "SELECT consumed FROM platform_admin_bootstrap WHERE singleton_id = 1 FOR UPDATE",
                Boolean.class
        );
        if (Boolean.TRUE.equals(consumed)) {
            return;
        }

        Integer adminCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE platform_role = 'PLATFORM_ADMIN'",
                Integer.class
        );
        if (adminCount != null && adminCount > 0) {
            markConsumed(jdbc);
            LOGGER.info("event=platform.admin.account_bootstrap.skipped reason=admin_exists");
            return;
        }

        BootstrapIdentity identity = validatedIdentity();
        rejectExistingIdentity(jdbc, identity.usernameKey());
        rejectExistingIdentity(jdbc, identity.emailKey());

        UUID emailAdminId = createAdminAccount(
                jdbc, identity.displayName(), identity.emailKey().normalizedIdentifier(),
                passwordEncoder.encode(identity.password()), "邮箱管理员的个人工作区"
        );
        UUID backupAdminId = createAdminAccount(
                jdbc, "备用管理员", null, passwordEncoder.encode(identity.password()),
                "用户名备用管理员的个人工作区"
        );
        insertIdentity(jdbc, emailAdminId, identity.emailKey());
        insertIdentity(jdbc, backupAdminId, identity.usernameKey());
        markConsumed(jdbc);

        LOGGER.info("event=platform.admin.accounts_bootstrapped primaryUserId={} backupUserId={}",
                emailAdminId, backupAdminId);
    }

    /** 每位管理员使用独立租户和工作区，避免备用账户共享主账户的业务数据作用域。 */
    private UUID createAdminAccount(JdbcTemplate jdbc, String name, String contactEmail,
            String passwordHash, String workspaceDescription) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenant (id, name, tenant_type, plan_code, status) "
                        + "VALUES (?, ?, 'PERSONAL', 'FREE', 'ACTIVE')",
                tenantId, fit(name + " 的个人账户", 120)
        );
        jdbc.update(
                "INSERT INTO user_account "
                        + "(id, tenant_id, email, display_name, password_hash, status, platform_role) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'PLATFORM_ADMIN')",
                userId, tenantId, contactEmail, name, passwordHash
        );
        jdbc.update(
                "INSERT INTO workspace (id, tenant_id, name, description, created_by, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                workspaceId, tenantId, fit(name + " 的工作区", 120), workspaceDescription, userId
        );
        jdbc.update(
                "INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')",
                workspaceId, userId
        );
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
            if (password == null || password.length() < 8
                    || password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_BYTES) {
                throw new IllegalArgumentException("invalid password length");
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
    private void rejectExistingIdentity(JdbcTemplate jdbc, UserIdentityKey key) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_identity "
                        + "WHERE identity_type = ? AND issuer = ? AND normalized_identifier = ?)",
                Boolean.class,
                key.type().name(),
                key.issuer(),
                key.normalizedIdentifier()
        );
        if (Boolean.TRUE.equals(exists)) {
            throw new IllegalStateException("管理员初始化失败：指定的用户名或邮箱已绑定到其他账户");
        }
    }

    /** 插入一条活动身份记录；邮箱与用户名分别绑定到独立账户。 */
    private void insertIdentity(JdbcTemplate jdbc, UUID userId, UserIdentityKey key) {
        jdbc.update(
                "INSERT INTO user_identity "
                        + "(id, user_id, identity_type, issuer, identifier, normalized_identifier, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')",
                UUID.randomUUID(),
                userId,
                key.type().name(),
                key.issuer(),
                key.normalizedIdentifier(),
                key.normalizedIdentifier()
        );
    }

    /** 持久化一次性标记，确保并发启动和后续重启不会重复创建或重置管理员。 */
    private void markConsumed(JdbcTemplate jdbc) {
        jdbc.update(
                "UPDATE platform_admin_bootstrap "
                        + "SET consumed = TRUE, consumed_at = CURRENT_TIMESTAMP WHERE singleton_id = 1"
        );
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
