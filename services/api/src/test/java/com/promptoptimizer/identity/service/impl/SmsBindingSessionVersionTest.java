package com.promptoptimizer.identity.service.impl;

import com.promptoptimizer.identity.domain.SmsChallengeState;
import com.promptoptimizer.identity.domain.SmsPurpose;
import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.entity.SmsChallenge;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import com.promptoptimizer.identity.mapper.SmsAccountMapper;
import com.promptoptimizer.identity.mapper.SmsChallengeMapper;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 手机绑定在云核验前后都复核账户锁下的认证版本，旧在途请求不能越过全设备登出。
 * @author QingNiao
 * @since 0.1.0
 */
class SmsBindingSessionVersionTest {
    private final SmsChallengeMapper challenges = mock(SmsChallengeMapper.class);
    private final SmsAccountMapper accounts = mock(SmsAccountMapper.class);
    private final UserAccountMapper users = mock(UserAccountMapper.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final SmsAccountTransactions transactions = new SmsAccountTransactions(challenges, accounts, users,
            mock(UserIdentityMapper.class), mock(IdentityProvisioningMapper.class), passwords);
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID identityId = UUID.randomUUID();
    private final UserAccountEntity account = new UserAccountEntity();
    private AuthenticatedUser actor;

    @BeforeEach
    void setUp() {
        actor = new AuthenticatedUser(userId, tenantId, UUID.randomUUID(), "synthetic@example.test", "synthetic@example.test",
                "模拟用户", "", "ACTIVE", List.of(new SimpleGrantedAuthority("ROLE_USER")), identityId, 7L);
        account.setId(userId); account.setTenantId(tenantId); account.setStatus("ACTIVE");
        account.setPasswordHash("synthetic-hash"); account.setSecurityVersion(7L);
        var identity = new UserIdentityEntity(); identity.setUserId(userId); identity.setStatus(UserIdentityStatus.ACTIVE);
        when(accounts.lockAccount(userId)).thenReturn(account);
        when(accounts.lockIdentity(identityId)).thenReturn(identity);
        when(users.selectDefaultWorkspaceId(userId, tenantId)).thenReturn(actor.actorIdentity().workspaceId());
        when(passwords.matches("synthetic-password", "synthetic-hash")).thenReturn(true);
    }

    @Test
    void currentNonzeroVersionCanStillUseExistingBindingChecks() {
        transactions.checkBindingActor(actor, "synthetic-password");
        verify(accounts).lockAccount(userId);
        verify(passwords).matches("synthetic-password", "synthetic-hash");
    }

    @Test
    void revokedVersionIsRejectedBeforeSendingOrCheckingPassword() {
        account.setSecurityVersion(8L);
        assertThatThrownBy(() -> transactions.checkBindingActor(actor, "synthetic-password"))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(passwords);
        verify(accounts, never()).insertPhone(any(), any(), any());
    }

    @Test
    void alreadyVerifiedCloudResultCannotBindAfterLogoutAllDuringNetworkWait() {
        UUID challengeId = UUID.randomUUID();
        when(challenges.find(challengeId)).thenReturn(new SmsChallenge(challengeId, SmsPurpose.BIND,
                "synthetic-phone-fingerprint", "synthetic-browser", userId, "synthetic-scheme", SmsChallengeState.VERIFIED,
                1, null, OffsetDateTime.now().plusMinutes(5), null, null, OffsetDateTime.now()));
        account.setSecurityVersion(8L);
        assertThatThrownBy(() -> transactions.complete(challengeId, SmsPurpose.BIND, "+8613800000000",
                "synthetic-phone-fingerprint", "synthetic-browser", actor, null, "synthetic-password", null))
                .isInstanceOf(BadCredentialsException.class);
        verify(accounts).lockAccount(userId);
        verify(accounts, never()).insertPhone(any(), any(), any());
        verify(challenges, never()).consume(any(), any(), any());
    }
}
