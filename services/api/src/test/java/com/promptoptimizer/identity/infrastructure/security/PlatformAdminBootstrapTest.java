package com.promptoptimizer.identity.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 管理员撤权后，遗留的初始化配置不得在重启时恢复权限。 */
class PlatformAdminBootstrapTest {

    @Test
    void consumedBootstrapNeverGrantsRoleAgain() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class)))
                .thenReturn(true);

        new PlatformAdminBootstrap(provider, "00000000-0000-0000-0000-000000000001").bootstrap();

        verify(jdbc).queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class));
        verifyNoMoreInteractions(jdbc);
    }
}
