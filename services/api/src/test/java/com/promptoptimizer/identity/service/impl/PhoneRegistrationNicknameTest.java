package com.promptoptimizer.identity.service.impl;

import com.promptoptimizer.identity.domain.*;
import com.promptoptimizer.identity.dto.SmsRequests;
import com.promptoptimizer.identity.entity.SmsChallenge;
import com.promptoptimizer.identity.infrastructure.sms.*;
import com.promptoptimizer.identity.mapper.*;
import com.promptoptimizer.identity.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实际短信注册服务与账户事务组合验证昵称流转，验证码供应商和持久化边界使用受控夹具。
 * @author QingNiao
 * @since 0.1.0
 */
class PhoneRegistrationNicknameTest {
    private final SmsChallengeMapper challenges = mock(SmsChallengeMapper.class);
    private final SmsChallengeTransactions verification = mock(SmsChallengeTransactions.class);
    private final SmsAccountMapper accounts = mock(SmsAccountMapper.class);
    private final IdentityProvisioningMapper provisioning = mock(IdentityProvisioningMapper.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final SmsFingerprint fingerprints = mock(SmsFingerprint.class);
    private final SmsVerificationProvider provider = mock(SmsVerificationProvider.class);
    private final PhoneVerificationService service;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final UUID challengeId = UUID.randomUUID();

    PhoneRegistrationNicknameTest() {
        var properties = new SmsProperties(); properties.setEnabled(true);
        var transactions = new SmsAccountTransactions(challenges, accounts, mock(UserAccountMapper.class),
                mock(UserIdentityMapper.class), provisioning, passwords);
        service = new PhoneVerificationService(properties, fingerprints, mock(RedisSmsRateLimiter.class), provider,
                verification, challenges, transactions, passwords, mock(LoginCaptchaService.class), mock(LoginFailureGuard.class));
        http.getSession().setAttribute("SMS_BROWSER_NONCE", "synthetic-browser");
        when(fingerprints.of(eq("phone"), anyString())).thenReturn("phone-hash");
        when(fingerprints.of(eq("browser"), anyString())).thenReturn("browser-hash");
        when(passwords.encode("Password1")).thenReturn("test-bcrypt-hash");
        when(accounts.insertPhone(any(), any(), anyString())).thenReturn(1);
        when(challenges.consume(any(), any(), anyString())).thenReturn(1);
        when(verification.reserve(eq(challengeId), eq(SmsPurpose.REGISTER), anyString(), anyString(), isNull(), any()))
                .thenReturn(challenge(SmsChallengeState.VERIFIED));
        when(challenges.find(challengeId)).thenReturn(challenge(SmsChallengeState.VERIFIED));
    }

    @Test
    void storesProvidedNicknameAlongsideTheExistingPhoneIdentity() {
        service.register(new SmsRequests.Registration("13800000000", challengeId, "123456", "Password1", "  星河🌟  "), http);
        verify(provisioning).insertUserAccount(any(), any(), isNull(), eq("星河🌟"), eq("test-bcrypt-hash"), eq("USER"));
        verify(accounts).insertPhone(any(), any(), eq("+8613800000000"));
        verifyNoInteractions(provider);
    }

    @Test
    void omittedNicknameKeepsTheOriginalDefault() {
        service.register(new SmsRequests.Registration("13800000000", challengeId, "123456", "Password1"), http);
        verify(provisioning).insertUserAccount(any(), any(), isNull(), eq("新用户"), eq("test-bcrypt-hash"), eq("USER"));
    }

    @Test
    void replayCannotReplaceTheOriginalNicknameOrCreateAnotherAccount() {
        service.register(new SmsRequests.Registration("13800000000", challengeId, "123456", "Password1", "原昵称"), http);
        when(verification.reserve(eq(challengeId), eq(SmsPurpose.REGISTER), anyString(), anyString(), isNull(), any()))
                .thenReturn(challenge(SmsChallengeState.CONSUMED));
        when(challenges.find(challengeId)).thenReturn(challenge(SmsChallengeState.CONSUMED));
        service.register(new SmsRequests.Registration("13800000000", challengeId, "123456", "Password1", "更改昵称"), http);
        verify(provisioning, times(1)).insertUserAccount(any(), any(), isNull(), eq("原昵称"), anyString(), eq("USER"));
        verify(provisioning, never()).insertUserAccount(any(), any(), isNull(), eq("更改昵称"), any(), anyString());
        verify(provisioning, times(1)).insertTenant(any(), anyString());
    }

    private SmsChallenge challenge(SmsChallengeState state) {
        return new SmsChallenge(challengeId, SmsPurpose.REGISTER, "phone-hash", "browser-hash", null, "test-scheme", state,
                0, null, OffsetDateTime.now().plusMinutes(5), UUID.randomUUID(), "OK", OffsetDateTime.now());
    }
}
