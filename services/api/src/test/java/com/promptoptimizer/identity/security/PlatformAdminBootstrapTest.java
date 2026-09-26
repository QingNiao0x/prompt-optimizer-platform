package com.promptoptimizer.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 管理员撤权后，遗留的初始化配置不得在重启时恢复权限。 */
class PlatformAdminBootstrapTest {

    @Test
    void consumedBootstrapNeverGrantsRoleAgain() {
        IdentityProvisioningMapper mapper = mock(IdentityProvisioningMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IdentityProvisioningMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        when(mapper.selectAdminBootstrapConsumedForUpdate()).thenReturn(true);

        new PlatformAdminBootstrap(provider, "00000000-0000-0000-0000-000000000001").bootstrap();

        verify(mapper).selectAdminBootstrapConsumedForUpdate();
        verifyNoMoreInteractions(mapper);
    }
}
