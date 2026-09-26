package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.identity.support.TestActors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;


import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 管理接口每次使用账户当前状态授权，不信任会话内旧角色。 */
class PlatformAdminAccessTest {

    @Test
    void requiresCurrentActivePlatformAdminRole() {
        UserAccountMapper repository = mock(UserAccountMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<UserAccountMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        UserAccountEntity account = new UserAccountEntity();
        account.setStatus("ACTIVE");
        account.setPlatformRole("PLATFORM_ADMIN");
        when(repository.selectById(TestActors.USER_ID)).thenReturn(account);
        PlatformAdminAccess access = new PlatformAdminAccess(TestActors.currentActor(), provider);

        assertThatCode(access::require).doesNotThrowAnyException();
        account.setPlatformRole("USER");
        assertThatThrownBy(access::require).isInstanceOf(AccessDeniedException.class);
        account.setPlatformRole("PLATFORM_ADMIN");
        account.setStatus("LOCKED");
        assertThatThrownBy(access::require).isInstanceOf(AccessDeniedException.class);
    }
}
