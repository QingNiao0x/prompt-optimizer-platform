package com.promptoptimizer.identity.dto;

import com.promptoptimizer.identity.controller.RegistrationController;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.AuthenticationService;
import com.promptoptimizer.identity.service.EmailRegistrationService;
import com.promptoptimizer.identity.service.RegistrationException;
import com.promptoptimizer.identity.security.SecurityConfiguration;
import com.promptoptimizer.identity.security.SecurityErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RegistrationController.class)
@Import({SecurityConfiguration.class, SecurityErrorWriter.class, RequestIdFilter.class})
class RegistrationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EmailRegistrationService registrationService;

    @MockBean
    private AuthenticationService authenticationService;

    @MockBean
    private UserDetailsService userDetailsService;

    @Test
    void registrationCodeIsAnonymousButStillRequiresCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/registration-code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isForbidden());

        when(registrationService.requestCode(any(), anyString()))
                .thenReturn(new EmailRegistrationCodeView(60, 300));
        mvc.perform(post("/api/v1/auth/registration-code")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300));
    }

    @Test
    void registerReturnsAuthenticatedUser() throws Exception {
        when(registrationService.register(any()))
                .thenReturn(new EmailRegistrationService.RegisteredEmail("new@example.com"));
        when(authenticationService.login(any(), any(), any())).thenReturn(user());

        mvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "new@example.com",
                                  "verificationCode": "123456",
                                  "password": "secure-password-123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("new@example.com"));
    }

    @Test
    void rateLimitIncludesRetryMetadataAndHeader() throws Exception {
        when(registrationService.requestCode(any(), anyString())).thenThrow(new RegistrationException(
                RegistrationException.Reason.RESEND_TOO_SOON,
                "验证码发送过于频繁，请稍后重试。",
                42
        ));

        mvc.perform(post("/api/v1/auth/registration-code")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_RESEND_TOO_SOON"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").value(42));
    }

    private AuthenticatedUserView user() {
        return new AuthenticatedUserView(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                UUID.fromString("00000000-0000-0000-0000-000000000013"),
                "new@example.com",
                "new"
        );
    }
}
