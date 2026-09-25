package com.promptoptimizer.identity.infrastructure.security;

import com.promptoptimizer.identity.infrastructure.persistence.UserAccountEntity;
import com.promptoptimizer.identity.infrastructure.persistence.UserAccountRepository;
import com.promptoptimizer.identity.support.TestActors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 管理接口每次使用账户当前状态授权，不信任会话内旧角色。 */
class PlatformAdminAccessTest {

    @Test
    void requiresCurrentActivePlatformAdminRole() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<UserAccountRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        UserAccountEntity account = new UserAccountEntity();
        account.setStatus("ACTIVE");
        account.setPlatformRole("PLATFORM_ADMIN");
        when(repository.findById(TestActors.USER_ID)).thenReturn(Optional.of(account));
        PlatformAdminAccess access = new PlatformAdminAccess(TestActors.currentActor(), provider);

        assertThatCode(access::require).doesNotThrowAnyException();
        account.setPlatformRole("USER");
        assertThatThrownBy(access::require).isInstanceOf(AccessDeniedException.class);
        account.setPlatformRole("PLATFORM_ADMIN");
        account.setStatus("LOCKED");
        assertThatThrownBy(access::require).isInstanceOf(AccessDeniedException.class);
    }
}
