package com.promptoptimizer.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import com.promptoptimizer.identity.service.impl.LoginCaptchaService;
import com.promptoptimizer.identity.support.TestActors;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真正经过密码校验、Session、CSRF、业务服务和内存会话存储的 HTTP 测试。 */
@SpringBootTest(properties = "app.security.local-user.password=local-test-password1")
@ActiveProfiles("local-mock")
@AutoConfigureMockMvc
@Import(AuthenticationIntegrationTest.Users.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationIntegrationTest {
    private static final String PASSWORD = "test-only-password-2026";
    private static final UUID OTHER_USER = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final String PROMPT = "给用户模块添加登录功能";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @TestConfiguration(proxyBeanMethods = false)
    static class Users {
        @Bean @Primary
        UserDetailsService users(PasswordEncoder encoder) {
            String hash = encoder.encode(PASSWORD);
            return email -> {
                if ("backend-failure@example.com".equals(email)) {
                    throw new DataAccessResourceFailureException(
                            "password=private-database-message",
                            new java.sql.SQLException("password=private-sql-message", "42P01", 0));
                }
                if (!List.of("alice@example.com", "bob@example.com", "locked@example.com").contains(email)) {
                    throw new UsernameNotFoundException("not found");
                }
                return new AuthenticatedUser(email.startsWith("bob") ? OTHER_USER : TestActors.USER_ID,
                        TestActors.TENANT_ID, TestActors.WORKSPACE_ID, email, email,
                        hash, email.startsWith("locked") ? "LOCKED" : "ACTIVE",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
            };
        }
    }

    @Test
    void rejectsAnonymousAndSpoofedIdentityButHealthAndCsrfArePublic() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("X-User-Id", TestActors.USER_ID))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        Cookie token = csrf();
        mvc.perform(post("/api/v1/optimizations/plan").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                        .header("X-User-Id", TestActors.USER_ID).contentType(APPLICATION_JSON)
                        .content("{\"rawPrompt\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryUserCannotManagePlatformModels() throws Exception {
        mvc.perform(get("/api/v1/admin/models")).andExpect(status().isUnauthorized());
        Login alice = login("alice@example.com", new MockHttpSession());
        mvc.perform(get("/api/v1/admin/models").session(alice.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingAndInvalidCsrfOnLoginAndAuthenticatedWrites() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(credentials("alice@example.com", PASSWORD)))
                .andExpect(status().isForbidden());
        Cookie token = csrf();
        mvc.perform(post("/api/v1/auth/login").cookie(token).header("X-XSRF-TOKEN", "incorrect")
                        .contentType(APPLICATION_JSON).content(credentials("alice@example.com", PASSWORD)))
                .andExpect(status().isForbidden());
        Login login = login("alice@example.com", new MockHttpSession());
        mvc.perform(post("/api/v1/context/planning").session(login.session()).contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void credentialsAreRequiredAndFailuresDoNotDiscloseAccountState() throws Exception {
        for (String email : List.of("alice@example.com", "unknown@example.com", "locked@example.com")) {
            Cookie token = csrf();
            MockHttpSession session = captchaSession();
            mvc.perform(post("/api/v1/auth/login").session(session).cookie(token)
                            .header("X-XSRF-TOKEN", token.getValue())
                            .contentType(APPLICATION_JSON)
                            .content(credentials(email, "incorrect-password1",
                                    (String) session.getAttribute(LoginCaptchaService.ATTRIBUTE))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_FAILED"));
        }
    }

    @Test
    void failedLoginLogsRequestIdAndFailureTypeWithoutCredentials(CapturedOutput output) throws Exception {
        Cookie token = csrf();
        MockHttpSession session = captchaSession();
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-Request-Id", "auth-failure-test-1")
                        .cookie(token).header("X-XSRF-TOKEN", token.getValue()).contentType(APPLICATION_JSON)
                        .content(credentials("alice@example.com", "incorrect-password1",
                                (String) session.getAttribute(LoginCaptchaService.ATTRIBUTE))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_FAILED"));

        assertThat(output)
                .contains("event=auth.authentication_rejected")
                .contains("requestId=auth-failure-test-1")
                .contains("failureType=BadCredentialsException")
                .doesNotContain("incorrect-password1");
    }

    @Test
    void authenticationInfrastructureFailureIsNotReportedAsWrongCredentials(CapturedOutput output) throws Exception {
        Cookie token = csrf();
        MockHttpSession session = captchaSession();
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-Request-Id", "auth-database-failure-1")
                        .cookie(token).header("X-XSRF-TOKEN", token.getValue()).contentType(APPLICATION_JSON)
                        .content(credentials("backend-failure@example.com", PASSWORD,
                                (String) session.getAttribute(LoginCaptchaService.ATTRIBUTE))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("登录服务暂时不可用，请稍后重试。"));

        assertThat(output)
                .contains("event=auth.authentication_service_failure")
                .contains("requestId=auth-database-failure-1")
                .contains("InternalAuthenticationServiceException")
                .contains("DataAccessResourceFailureException")
                .contains("sqlState=42P01")
                .doesNotContain("backend-failure@example.com", "private-database-message",
                        "private-sql-message", PASSWORD);
    }

    @Test
    void loginRotatesSessionAndTokenErasesCredentialsAndLogoutInvalidatesSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String oldId = session.getId();
        Login login = login("alice@example.com", session);
        assertThat(login.session().getId()).isNotEqualTo(oldId);
        SecurityContext context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(((AuthenticatedUser) context.getAuthentication().getPrincipal()).getPassword()).isNull();
        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(TestActors.USER_ID.toString()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(write("/api/v1/auth/logout", login).content("{}"))
                .andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        // 退出后同一账户可以再次登录；密码擦除不会破坏后续认证。
        login("alice@example.com", new MockHttpSession());
    }

    @Test
    void contextAndPlanAreOwnedByAuthenticatedUserEvenWithinSameWorkspace() throws Exception {
        Login alice = login("alice@example.com", new MockHttpSession());
        Login bob = login("bob@example.com", new MockHttpSession());
        JsonNode prepared = data(mvc.perform(write("/api/v1/context/planning", alice)
                        .content(mapper.writeValueAsString(Map.of("rawPrompt", PROMPT,
                                "context", Map.of("customDescription", "", "files", List.of())))))
                .andExpect(status().isOk()).andReturn());
        Map<String, String> reference = Map.of("contextId", prepared.path("contextId").asText(),
                "version", prepared.path("version").asText());
        String request = mapper.writeValueAsString(Map.of("rawPrompt", PROMPT, "planningContext", reference,
                "userId", TestActors.USER_ID));
        JsonNode plan = data(mvc.perform(write("/api/v1/optimizations/plan", alice).content(request))
                .andExpect(status().isOk()).andReturn());
        String eventBody = mapper.writeValueAsString(Map.of(
                "planId", plan.path("planId").asText(), "event", "CANCELLED"));
        mvc.perform(write("/api/v1/optimizations/plan-events", bob).content(eventBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PLANNING_SESSION_EXPIRED"));
        mvc.perform(write("/api/v1/optimizations/plan-events", alice).content(eventBody))
                .andExpect(status().isNoContent());
        mvc.perform(write("/api/v1/optimizations/plan", bob).content(request))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PLANNING_SESSION_EXPIRED"))
                .andExpect(jsonPath("$.error.message").value("文件上下文已过期，请重新分析。"));
        ObjectNode confirmation = mapper.createObjectNode();
        confirmation.put("planId", plan.path("planId").asText());
        confirmation.set("planningContext", mapper.valueToTree(reference));
        var answers = confirmation.putArray("answers");
        for (JsonNode question : plan.path("questions")) {
            answers.addObject().put("questionId", question.path("id").asText())
                    .put("question", question.path("question").asText()).put("answer", "实现基础邮箱密码登录");
        }
        ObjectNode optimize = mapper.createObjectNode().put("rawPrompt", PROMPT);
        optimize.set("planConfirmation", confirmation);
        String body = mapper.writeValueAsString(optimize);
        mvc.perform(write("/api/v1/optimizations", bob).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PLANNING_SESSION_EXPIRED"))
                .andExpect(jsonPath("$.error.message").value("确认问题已过期，请重新生成。"));
        mvc.perform(write("/api/v1/optimizations", alice).content(body)).andExpect(status().isOk());
        // 新会话仍以稳定 userId 认领，不能把会话 cookie 本身当作资源所有者。
        Login aliceAgain = login("alice@example.com", new MockHttpSession());
        mvc.perform(write("/api/v1/optimizations", aliceAgain).content(body)).andExpect(status().isOk());
    }

    private Cookie csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(data(result).path("token").asText()).isEqualTo(cookie.getValue());
        return cookie;
    }

    private Login login(String email, MockHttpSession session) throws Exception {
        Cookie token = csrf();
        mvc.perform(get("/api/v1/auth/captcha").session(session)).andExpect(status().isOk());
        Object captcha = session.getAttribute(LoginCaptchaService.ATTRIBUTE);
        assertThat(captcha).isInstanceOf(String.class);
        MvcResult result = mvc.perform(post("/api/v1/auth/login").session(session).cookie(token)
                        .header("X-XSRF-TOKEN", token.getValue()).contentType(APPLICATION_JSON)
                        .content(credentials(email, PASSWORD, (String) captcha)))
                .andExpect(status().isOk()).andReturn();
        Cookie rotated = null;
        for (Cookie cookie : result.getResponse().getCookies()) {
            if ("XSRF-TOKEN".equals(cookie.getName()) && cookie.getMaxAge() != 0) {
                rotated = cookie;
            }
        }
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(token.getValue());
        return new Login((MockHttpSession) result.getRequest().getSession(false), rotated);
    }

    private String credentials(String email, String password) throws Exception {
        return credentials(email, password, "0000");
    }

    private String credentials(String email, String password, String captcha) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", password, "captcha", captcha));
    }

    private MockHttpSession captchaSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/api/v1/auth/captcha").session(session)).andExpect(status().isOk());
        return session;
    }

    private MockHttpServletRequestBuilder write(String path, Login login) {
        return post(path).session(login.session()).cookie(login.token())
                .header("X-XSRF-TOKEN", login.token().getValue()).contentType(APPLICATION_JSON);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private record Login(MockHttpSession session, Cookie token) { }
}
