package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.application.EmailVerificationPolicy;
import com.promptoptimizer.identity.application.EmailVerificationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HybridEmailVerificationStoreTest {

    @Test
    void shouldUseMemoryDirectlyWhenLocalLogModeAllowsRedisFallback() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);

        RegistrationProperties properties = new RegistrationProperties();
        properties.setDeliveryMode("log");
        properties.setRequireRedis(false);
        HybridEmailVerificationStore store = new HybridEmailVerificationStore(provider, properties);
        EmailVerificationPolicy policy = new EmailVerificationPolicy(
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                5,
                5,
                20
        );

        assertThat(store.issue("email", "ip", "digest", policy).result())
                .isEqualTo(EmailVerificationStore.IssueResult.ISSUED);
        assertThat(store.verify("email", "digest"))
                .isEqualTo(EmailVerificationStore.VerificationResult.VALID);
        verifyNoInteractions(redisTemplate);
    }
}
