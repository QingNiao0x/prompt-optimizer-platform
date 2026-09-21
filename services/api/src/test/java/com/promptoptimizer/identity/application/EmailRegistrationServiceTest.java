package com.promptoptimizer.identity.application;

import com.promptoptimizer.identity.api.EmailRegistrationCodeRequest;
import com.promptoptimizer.identity.api.EmailRegistrationRequest;
import com.promptoptimizer.identity.infrastructure.persistence.UserIdentityRepository;
import com.promptoptimizer.identity.infrastructure.registration.RegistrationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailRegistrationServiceTest {

    private final UserIdentityRepository identityRepository = mock(UserIdentityRepository.class);
    private final AccountRegistrationGateway registrationGateway = mock(AccountRegistrationGateway.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final RegistrationProperties properties = new RegistrationProperties();
    private final InMemoryEmailVerificationStore store = new InMemoryEmailVerificationStore();
    private final AtomicReference<String> deliveredCode = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        properties.setVerificationSecret("test-only-verification-secret-with-32-bytes");
        properties.setRequireRedis(false);
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
    }

    @Test
    void sendsCodeThenCreatesAccountAndConsumesCode() {
        EmailRegistrationService service = service((recipient, code, validFor) -> deliveredCode.set(code));

        assertThat(service.requestCode(
                new EmailRegistrationCodeRequest("New@Example.com"),
                "127.0.0.1"
        ).expiresInSeconds()).isEqualTo(300);
        assertThat(deliveredCode.get()).matches("\\d{6}");

        EmailRegistrationService.RegisteredEmail registered = service.register(
                new EmailRegistrationRequest(
                        "new@example.com",
                        deliveredCode.get(),
                        "secure-password-123"
                )
        );

        assertThat(registered.email()).isEqualTo("new@example.com");
        verify(registrationGateway).createPersonalAccount(
                org.mockito.ArgumentMatchers.eq("new@example.com"),
                org.mockito.ArgumentMatchers.eq("new"),
                org.mockito.ArgumentMatchers.eq("bcrypt-hash"),
                any()
        );
        assertThatThrownBy(() -> service.register(new EmailRegistrationRequest(
                "new@example.com",
                deliveredCode.get(),
                "secure-password-123"
        ))).isInstanceOf(RegistrationException.class)
                .extracting(exception -> ((RegistrationException) exception).getReason())
                .isEqualTo(RegistrationException.Reason.CODE_EXPIRED);
    }

    @Test
    void rejectsPasswordsShorterThanEightCharactersBeforeCreatingAccount() {
        EmailRegistrationService service = service((recipient, code, validFor) -> deliveredCode.set(code));
        service.requestCode(new EmailRegistrationCodeRequest("new@example.com"), "127.0.0.1");

        assertThatThrownBy(() -> service.register(new EmailRegistrationRequest(
                "new@example.com",
                deliveredCode.get(),
                "1234567"
        ))).isInstanceOf(RegistrationException.class)
                .extracting(exception -> ((RegistrationException) exception).getReason())
                .isEqualTo(RegistrationException.Reason.PASSWORD_INVALID);
        verify(registrationGateway, never()).createPersonalAccount(
                anyString(), anyString(), anyString(), any()
        );
    }

    @Test
    void rejectsPasswordsLongerThanBcryptLimitBeforeCreatingAccount() {
        EmailRegistrationService service = service((recipient, code, validFor) -> deliveredCode.set(code));
        service.requestCode(new EmailRegistrationCodeRequest("new@example.com"), "127.0.0.1");

        assertThatThrownBy(() -> service.register(new EmailRegistrationRequest(
                "new@example.com",
                deliveredCode.get(),
                "中".repeat(25)
        ))).isInstanceOf(RegistrationException.class)
                .extracting(exception -> ((RegistrationException) exception).getReason())
                .isEqualTo(RegistrationException.Reason.PASSWORD_INVALID);
        verify(registrationGateway, never()).createPersonalAccount(
                anyString(), anyString(), anyString(), any()
        );
    }

    @Test
    void rollsBackThrottleReservationWhenDeliveryFails() {
        EmailRegistrationService service = service((recipient, code, validFor) -> {
            throw new IllegalStateException("mail unavailable");
        });

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.requestCode(
                    new EmailRegistrationCodeRequest("new@example.com"),
                    "127.0.0.1"
            )).isInstanceOf(RegistrationException.class)
                    .extracting(exception -> ((RegistrationException) exception).getReason())
                    .isEqualTo(RegistrationException.Reason.DELIVERY_UNAVAILABLE);
        }
    }

    private EmailRegistrationService service(VerificationEmailSender sender) {
        return new EmailRegistrationService(
                identityRepository,
                store,
                sender,
                properties,
                registrationGateway,
                passwordEncoder
        );
    }
}
