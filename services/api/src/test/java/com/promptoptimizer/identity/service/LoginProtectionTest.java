package com.promptoptimizer.identity.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 验证密码规则、图形验证码作废和连续失败锁定。 */
class LoginProtectionTest {

    @Test
    void passwordMustContainLetterAndDigit() {
        assertThat(PasswordPolicy.meets("password")).isFalse();
        assertThat(PasswordPolicy.meets("12345678")).isFalse();
        assertThat(PasswordPolicy.meets("short1")).isFalse();
        assertThat(PasswordPolicy.meets("password1")).isTrue();
    }

    @Test
    void captchaIsAcceptedOnce() {
        LoginCaptchaService service = new LoginCaptchaService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        byte[] image = service.issue(request);
        String code = (String) request.getSession().getAttribute(LoginCaptchaService.ATTRIBUTE);

        assertThat(image).isNotEmpty();
        service.verifyAndConsume(request, code.toLowerCase());
        assertThatThrownBy(() -> service.verifyAndConsume(request, code))
                .isInstanceOf(LoginGuardException.class)
                .extracting(error -> ((LoginGuardException) error).code())
                .isEqualTo("CAPTCHA_INVALID");
    }

    @Test
    void fifthFailureLocksTheIdentifier() {
        LoginFailureGuard guard = new LoginFailureGuard(emptyRedis(), false, 5, 30, Duration.ofMinutes(10));

        for (int attempt = 0; attempt < 4; attempt++) {
            guard.recordFailure("alice@example.com", "127.0.0.1");
        }
        assertThatThrownBy(() -> guard.recordFailure("alice@example.com", "127.0.0.1"))
                .isInstanceOf(LoginGuardException.class)
                .extracting(error -> ((LoginGuardException) error).code())
                .isEqualTo("LOGIN_LOCKED");
        assertThatThrownBy(() -> guard.checkAllowed("alice@example.com", "10.0.0.8"))
                .isInstanceOf(LoginGuardException.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> emptyRedis() {
        return mock(ObjectProvider.class);
    }
}
