package com.promptoptimizer.identity.service;

import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 可信回调扩展点只同步关联资料；没有微信网络请求或客户端身份指定入口。 */
class WechatNicknameSynchronizerTest {
    private final UserIdentityMapper identities = mock(UserIdentityMapper.class);
    private final WechatNicknameSynchronizer service = new WechatNicknameSynchronizer(identities);

    @Test
    void syncsValidatedUnicodeNameUsingTheExactProviderIdentity() {
        when(identities.syncWechatNickname(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        assertThat(service.syncVerifiedNickname("test-app", "Subject-AbC", "  微信用户🌟  ")).isTrue();
        var timestamp = ArgumentCaptor.forClass(String.class);
        verify(identities).syncWechatNickname(eq("test-app"), eq("Subject-AbC"), eq("微信用户🌟"), timestamp.capture());
        assertThat(OffsetDateTime.parse(timestamp.getValue()).getOffset().getTotalSeconds()).isZero();
    }

    @Test
    void doesNotCreateOrReactivateAnUnboundOrRevokedIdentity() {
        when(identities.syncWechatNickname(anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        assertThat(service.syncVerifiedNickname("test-app", "missing-subject", "微信用户")).isFalse();
        verify(identities).syncWechatNickname(eq("test-app"), eq("missing-subject"), eq("微信用户"), anyString());
        verifyNoMoreInteractions(identities);
    }

    @Test
    void emptyOrInvalidProviderNameCannotEraseExistingProfile() {
        assertThat(service.syncVerifiedNickname("test-app", "subject", "   ")).isFalse();
        assertThatThrownBy(() -> service.syncVerifiedNickname("test-app", "subject", "名".repeat(81)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(identities);
    }
}
