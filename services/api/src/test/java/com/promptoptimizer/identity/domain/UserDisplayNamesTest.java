package com.promptoptimizer.identity.domain;

import com.promptoptimizer.identity.dto.EmailRegistrationRequest;
import com.promptoptimizer.identity.dto.SmsRequests;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 注册资料的可选、边界及Unicode保真验证，不发送验证码或创建实际账号。 */
class UserDisplayNamesTest {
    @Test
    void optionalNamesKeepDefaultsAndPreserveUnicode() {
        assertThat(UserDisplayNames.resolve(null, "原默认名")).isEqualTo("原默认名");
        assertThat(UserDisplayNames.resolve("　 \t ", "原默认名")).isEqualTo("原默认名");
        assertThat(UserDisplayNames.resolve("  星河 🌟  ", "原默认名")).isEqualTo("星河 🌟");
        assertThat(UserDisplayNames.resolve("名".repeat(80), "原默认名")).hasSize(80);
    }

    @Test
    void bothRegistrationDtosAcceptOmittedNamesAndRejectInvalidNames() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String nickname : new String[] {"名".repeat(81), "甲\n乙"}) {
                assertThat(validator.validate(new EmailRegistrationRequest("test@example.test", "123456", "Password1", nickname)))
                        .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("displayName"));
                assertThat(validator.validate(new SmsRequests.Registration("13800000000", UUID.randomUUID(), "123456", "Password1", nickname)))
                        .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("displayName"));
            }
            assertThat(validator.validate(new EmailRegistrationRequest("test@example.test", "123456", "Password1"))).isEmpty();
            assertThat(validator.validate(new SmsRequests.Registration("13800000000", UUID.randomUUID(), "123456", "Password1", "  "))).isEmpty();
        }
    }

    @Test
    void internalCallersCannotBypassNameLimits() {
        assertThatThrownBy(() -> UserDisplayNames.resolve("名".repeat(81), "默认"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserDisplayNames.resolve("甲\u0000乙", "默认"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registrationDiagnosticTextDoesNotExposeProfileOrCredentials() {
        assertThat(new EmailRegistrationRequest("test@example.test", "123456", "Password1", "用户昵称").toString())
                .isEqualTo("EmailRegistrationRequest[redacted]");
        assertThat(new SmsRequests.Registration("13800000000", UUID.randomUUID(), "123456", "Password1", "用户昵称").toString())
                .isEqualTo("PhoneRegistrationRequest[redacted]");
    }
}
