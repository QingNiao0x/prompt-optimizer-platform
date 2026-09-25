package com.promptoptimizer.identity.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/** 验证首次启动时邮箱管理员和用户名备用管理员分别拥有独立账户。 */
class BootstrapAdminAccountInitializerTest {

    @Test
    @SuppressWarnings("unchecked")
    void createsSeparatePrimaryAndBackupAdminAccounts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        when(jdbc.queryForObject(
                "SELECT consumed FROM platform_admin_bootstrap WHERE singleton_id = 1 FOR UPDATE",
                Boolean.class
        )).thenReturn(false);
        when(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE platform_role = 'PLATFORM_ADMIN'",
                Integer.class
        )).thenReturn(0);
        var encoder = new BCryptPasswordEncoder(4);
        var initializer = new BootstrapAdminAccountInitializer(
                provider, encoder, true, "admin", "primary@example.com", "Admin", "test-only-password"
        );

        initializer.run(null);

        List<Object[]> accounts = updateArguments(jdbc, "INSERT INTO user_account ");
        List<Object[]> identities = updateArguments(jdbc, "INSERT INTO user_identity ");
        assertThat(accounts).hasSize(2);
        assertThat(identities).hasSize(2);
        UUID primaryId = (UUID) accounts.get(0)[1];
        UUID backupId = (UUID) accounts.get(1)[1];
        assertThat(primaryId).isNotEqualTo(backupId);
        assertThat(accounts.get(0)[3]).isEqualTo("primary@example.com");
        assertThat(accounts.get(1)[3]).isNull();
        assertThat(encoder.matches("test-only-password", (String) accounts.get(0)[5])).isTrue();
        assertThat(encoder.matches("test-only-password", (String) accounts.get(1)[5])).isTrue();
        assertThat(identities.get(0)[2]).isEqualTo(primaryId);
        assertThat(identities.get(0)[3]).isEqualTo("EMAIL");
        assertThat(identities.get(1)[2]).isEqualTo(backupId);
        assertThat(identities.get(1)[3]).isEqualTo("USERNAME");
    }

    /** 只检查相关 INSERT 的入参，不把密码或哈希输出到测试日志。 */
    private List<Object[]> updateArguments(JdbcTemplate jdbc, String sqlPrefix) {
        return mockingDetails(jdbc).getInvocations().stream()
                .map(Invocation::getArguments)
                .filter(arguments -> arguments.length > 0 && arguments[0] instanceof String sql
                        && sql.startsWith(sqlPrefix))
                .toList();
    }
}
