package com.promptoptimizer.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserIdentityKeyTest {

    @Test
    void emailIdentityShouldNormalizeCaseAndWhitespace() {
        UserIdentityKey key = UserIdentityKey.email(" Alice@Example.COM ");

        assertThat(key.type()).isEqualTo(UserIdentityType.EMAIL);
        assertThat(key.issuer()).isEqualTo(UserIdentityKey.LOCAL_ISSUER);
        assertThat(key.normalizedIdentifier()).isEqualTo("alice@example.com");
    }

    @Test
    void phoneIdentityShouldRequireE164Format() {
        assertThat(UserIdentityKey.phone(" +8613800000000 ").normalizedIdentifier())
                .isEqualTo("+8613800000000");
        assertThatThrownBy(() -> UserIdentityKey.phone("13800000000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wechatIdentityShouldKeepProviderSubjectCaseSensitive() {
        UserIdentityKey key = UserIdentityKey.wechat(" wx-example-app ", " UnionId-AbC ");

        assertThat(key.issuer()).isEqualTo("wx-example-app");
        assertThat(key.normalizedIdentifier()).isEqualTo("UnionId-AbC");
    }

    @Test
    void identityShouldRejectBlankIssuerOrIdentifier() {
        assertThatThrownBy(() -> UserIdentityKey.wechat(" ", "union-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserIdentityKey.email(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
