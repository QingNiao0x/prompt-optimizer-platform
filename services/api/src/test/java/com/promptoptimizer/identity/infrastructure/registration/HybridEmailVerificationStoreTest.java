package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.service.EmailVerificationPolicy;
import com.promptoptimizer.identity.service.EmailVerificationStore;
import com.promptoptimizer.identity.service.RegistrationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HybridEmailVerificationStoreTest {

    @Test
    void shouldFailClosedWhenRequiredRedisIsMissing() {
        var beans = new StaticListableBeanFactory();
        var properties = new RegistrationProperties();
        properties.setDeliveryMode("smtp");
        properties.setRequireRedis(true);
        var store = new HybridEmailVerificationStore(beans.getBeanProvider(StringRedisTemplate.class), properties);

        assertThatThrownBy(() -> store.issue("synthetic-email", "synthetic-ip", "synthetic-digest", properties.policy()))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(RegistrationException.Reason.SERVICE_UNAVAILABLE));
    }

    @Test
    void shouldNotFallbackWhenRequiredRedisCommandFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, invocation -> {
            throw new RedisSystemException("synthetic-redis-command-failure", new IllegalStateException("synthetic-cause"));
        });
        var beans = new StaticListableBeanFactory();
        beans.addBean("redisTemplate", redis);
        var properties = new RegistrationProperties();
        properties.setDeliveryMode("smtp");
        properties.setRequireRedis(true);
        var store = new HybridEmailVerificationStore(beans.getBeanProvider(StringRedisTemplate.class), properties);

        assertThatThrownBy(() -> store.issue("synthetic-email", "synthetic-ip", "synthetic-digest", properties.policy()))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(RegistrationException.Reason.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> store.verify("synthetic-email", "synthetic-digest"))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(RegistrationException.Reason.SERVICE_UNAVAILABLE));
    }

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
