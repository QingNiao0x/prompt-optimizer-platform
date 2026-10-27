package com.promptoptimizer.identity.settings;

import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 设置事务的账户、身份、验证码及最后管理员保护；真实事务原子性另由独立PostgreSQL验收覆盖。
 * @author QingNiao
 * @since 0.1.0
 */
class AccountSettingsTransactionsTest {
    private final AccountSettingsMapper mapper = mock(AccountSettingsMapper.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AccountSettingsTransactions transactions = new AccountSettingsTransactions(mapper, passwords);
    private final UUID userId = UUID.randomUUID();
    private final UUID identityId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private AuthenticatedUser principal;
    private UserAccountEntity account;

    @BeforeEach
    void setUp() {
        principal = new AuthenticatedUser(userId, tenantId, UUID.randomUUID(), "test@example.invalid", "test@example.invalid",
                "测试用户", "", "ACTIVE", List.of(new SimpleGrantedAuthority("ROLE_USER")), identityId, 3L);
        account = new UserAccountEntity();
        account.setId(userId); account.setTenantId(tenantId); account.setStatus("ACTIVE");
        account.setDisplayName("测试用户"); account.setPasswordHash("synthetic-hash"); account.setSecurityVersion(3L);
        var identity = new UserIdentityEntity();
        identity.setId(identityId); identity.setUserId(userId); identity.setStatus(UserIdentityStatus.ACTIVE);
        when(mapper.account(eq(userId), anyBoolean())).thenReturn(account);
        when(mapper.identity(identityId)).thenReturn(identity);
        when(passwords.matches("synthetic-password", "synthetic-hash")).thenReturn(true);
    }

    @Test
    void rejectsRevokedIdentityAndStaleSessionBeforeAnyWrite() {
        account.setSecurityVersion(4L);
        assertThatThrownBy(() -> transactions.profile(principal, "新昵称")).isInstanceOf(AccountSettingsException.class)
                .extracting("code").isEqualTo("SESSION_REVOKED");
        verify(mapper, never()).profile(any(), any());
        account.setSecurityVersion(3L);
        when(mapper.identity(identityId)).thenReturn(null);
        assertThatThrownBy(() -> transactions.logoutAll(principal, "synthetic-password")).isInstanceOf(AccountSettingsException.class);
        verify(mapper, never()).securityChange(any(), any(), anyBoolean());
    }

    @Test
    void rejectsOtherOwnersAndRevokedKeysWithoutChangingEitherAccount() {
        var occupied = new UserIdentityEntity();
        occupied.setUserId(UUID.randomUUID()); occupied.setStatus(UserIdentityStatus.REVOKED);
        when(mapper.identityKey("USERNAME", "reserved-name")).thenReturn(occupied);
        assertThatThrownBy(() -> transactions.username(principal, "synthetic-password", "reserved-name"))
                .isInstanceOf(AccountSettingsException.class).extracting("code").isEqualTo("USERNAME_ALREADY_TAKEN");
        verify(mapper, never()).revokeType(any(), any());
        verify(mapper, never()).insertIdentity(any(), any(), any(), any());
    }

    @Test
    void wrongFifthCodeConsumesAttemptBudgetButNeverMutatesIdentity() {
        UUID challengeId = UUID.randomUUID();
        when(mapper.challenge(challengeId)).thenReturn(new EmailBindingChallenge(challengeId, userId,
                "target", "browser", "correct", "SENT", 4, OffsetDateTime.now().plusMinutes(5)));
        assertThatThrownBy(() -> transactions.email(principal, "synthetic-password", challengeId,
                "new@example.invalid", "target", "browser", "wrong"))
                .isInstanceOf(AccountSettingsException.CodeRejected.class).extracting("code").isEqualTo("EMAIL_CODE_ATTEMPTS_EXHAUSTED");
        verify(mapper).badAttempt(challengeId);
        verify(mapper, never()).consume(any());
        verify(mapper, never()).revokeType(any(), any());
    }

    @Test
    void wrongBrowserOrExpiredChallengeCannotAuthorizeBinding() {
        UUID id = UUID.randomUUID();
        when(mapper.challenge(id)).thenReturn(new EmailBindingChallenge(id, userId, "target", "browser", "code",
                "SENT", 0, OffsetDateTime.now().minusSeconds(1)));
        assertThatThrownBy(() -> transactions.email(principal, "synthetic-password", id, "new@example.invalid", "target", "other", "code"))
                .isInstanceOf(AccountSettingsException.class);
        assertThatThrownBy(() -> transactions.email(principal, "synthetic-password", id, "new@example.invalid", "target", "browser", "code"))
                .isInstanceOf(AccountSettingsException.class).extracting("code").isEqualTo("EMAIL_CODE_EXPIRED");
        verify(mapper, never()).badAttempt(any());
        verify(mapper, never()).consume(any());
    }

    @Test
    void correctCodeReplacesIdentityAndConsumesWithinSameTransactionMethod() {
        UUID id = UUID.randomUUID();
        when(mapper.challenge(id)).thenReturn(new EmailBindingChallenge(id, userId, "target", "browser", "code",
                "SENT", 0, OffsetDateTime.now().plusMinutes(5)));
        when(mapper.insertIdentity(any(), eq(userId), eq("EMAIL"), eq("new@example.invalid"))).thenReturn(1);
        when(mapper.consume(id)).thenReturn(1);
        transactions.email(principal, "synthetic-password", id, "new@example.invalid", "target", "browser", "code");
        verify(mapper).revokeType(userId, "EMAIL");
        verify(mapper).securityChange(userId, "new@example.invalid", true);
        verify(mapper).consume(id);
        assertThat(account.getSecurityVersion()).isEqualTo(4L);
    }

    @Test
    void lastActiveAdminCannotDeactivateButOrdinaryAccountKeepsBusinessResources() {
        account.setPlatformRole("PLATFORM_ADMIN");
        when(mapper.activeAdminCount()).thenReturn(1);
        assertThatThrownBy(() -> transactions.deactivate(principal, "synthetic-password", "DELETE"))
                .isInstanceOf(AccountSettingsException.class).extracting("code").isEqualTo("LAST_PLATFORM_ADMIN");
        verify(mapper, never()).revokeAll(any());
        account.setPlatformRole("USER");
        transactions.deactivate(principal, "synthetic-password", "DELETE");
        verify(mapper).revokeAll(userId);
        verify(mapper).deactivate(userId);
    }

    @Test
    void passwordAndDisplayRulesApplyBeforeWrites() {
        assertThatThrownBy(() -> transactions.username(principal, "incorrect", "new-name"))
                .isInstanceOf(AccountSettingsException.class).extracting("code").isEqualTo("CURRENT_PASSWORD_INVALID");
        assertThatThrownBy(() -> transactions.profile(principal, "bad\nname")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transactions.locale(principal, "fr")).isInstanceOf(AccountSettingsException.class);
        verify(mapper, never()).insertIdentity(any(), any(), any(), any());
        verify(mapper, never()).profile(any(), any());
        verify(mapper, never()).locale(any(), any());
    }
}
