package com.promptoptimizer.identity.settings;

import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 会话快照必须同时满足版本、账户、身份和角色；展示资料刷新不能获得新权限。
 * @author QingNiao
 * @since 0.1.0
 */
class AccountSessionValidatorTest {
    @Test
    void refreshesNameButRejectsEveryRevocationAndRoleChange() {
        var mapper = mock(AccountSettingsMapper.class);
        var validator = new AccountSessionValidator(mapper);
        UUID id = UUID.randomUUID(), tenant = UUID.randomUUID(), identityId = UUID.randomUUID();
        var principal = new AuthenticatedUser(id, tenant, UUID.randomUUID(), "old@example.invalid", "old@example.invalid",
                "旧昵称", "", "ACTIVE", List.of(new SimpleGrantedAuthority("ROLE_USER")), identityId, 2L);
        var account = new UserAccountEntity();
        account.setId(id); account.setTenantId(tenant); account.setStatus("ACTIVE");
        account.setDisplayName("新昵称"); account.setEmail("old@example.invalid"); account.setSecurityVersion(2L);
        var identity = new UserIdentityEntity(); identity.setUserId(id); identity.setStatus(UserIdentityStatus.ACTIVE);
        when(mapper.account(id, false)).thenReturn(account);
        when(mapper.identity(identityId)).thenReturn(identity);
        var refreshed = validator.refresh(principal);
        assertThat(refreshed.actorIdentity().displayName()).isEqualTo("新昵称");
        assertThat(refreshed.getPassword()).isNull();
        account.setSecurityVersion(3L); assertThat(validator.valid(principal)).isFalse();
        account.setSecurityVersion(2L); identity.setStatus(UserIdentityStatus.REVOKED); assertThat(validator.valid(principal)).isFalse();
        identity.setStatus(UserIdentityStatus.ACTIVE); account.setPlatformRole("PLATFORM_ADMIN"); assertThat(validator.valid(principal)).isFalse();
        account.setPlatformRole("USER"); account.setDeletedAt(OffsetDateTime.now()); assertThat(validator.valid(principal)).isFalse();
    }
}
