package com.promptoptimizer.identity.mapper;

import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.domain.UserIdentityType;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 SQL 确认账户与登录身份只按登录键读取，密码更新不改角色或状态。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootTest(properties = {
        "spring.session.store-type=none",
        "app.security.login-guard.require-redis=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@Transactional
class UserAccountScopeDatabaseTest {

    @Autowired
    private UserAccountMapper accountMapper;

    @Autowired
    private UserIdentityMapper identityMapper;

    @Autowired
    private DataSource dataSource;

    @Test
    void loginLookupAndPasswordUpdateStayNarrow() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        String email = "scope-" + userId + "@example.com";
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenantId, "account-scope");
        jdbc.update(
                """
                INSERT INTO user_account (id, tenant_id, email, display_name, password_hash, status, platform_role)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'USER')
                """,
                userId,
                tenantId,
                email,
                "Scope",
                "original-hash"
        );
        jdbc.update(
                """
                INSERT INTO user_identity (
                    id, user_id, identity_type, issuer, identifier, normalized_identifier, status
                ) VALUES (?, ?, 'EMAIL', 'local', ?, ?, 'ACTIVE')
                """,
                identityId,
                userId,
                email,
                email
        );

        UserIdentityEntity identity = identityMapper.selectByLoginKey(UserIdentityType.EMAIL, "local", email);
        assertThat(identity).isNotNull();
        assertThat(identity.getUserId()).isEqualTo(userId);
        assertThat(identity.getStatus()).isEqualTo(UserIdentityStatus.ACTIVE);
        assertThat(identityMapper.existsByLoginKey(UserIdentityType.EMAIL, "local", email)).isTrue();
        assertThat(identityMapper.existsByLoginKey(UserIdentityType.EMAIL, "local", "missing-" + email)).isFalse();

        UserAccountEntity account = accountMapper.selectById(userId);
        assertThat(account.getTenantId()).isEqualTo(tenantId);
        assertThat(account.getPlatformRole()).isEqualTo("USER");
        assertThat(account.getPasswordHash()).isEqualTo("original-hash");

        assertThat(accountMapper.updatePasswordHash(userId, "replacement-hash")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM user_account WHERE id = ?",
                String.class,
                userId
        )).isEqualTo("replacement-hash");
        assertThat(jdbc.queryForObject(
                "SELECT platform_role || ':' || status FROM user_account WHERE id = ?",
                String.class,
                userId
        )).isEqualTo("USER:ACTIVE");
    }
}
