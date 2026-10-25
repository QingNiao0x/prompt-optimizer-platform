package com.promptoptimizer.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.identity.controller.AuthenticationController;
import com.promptoptimizer.identity.security.*;
import com.promptoptimizer.identity.service.LoginCaptchaService;
import com.promptoptimizer.identity.service.impl.AuthenticationServiceImpl;
import com.promptoptimizer.identity.service.impl.LoginFailureGuard;
import com.promptoptimizer.identity.support.TestActors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionProperties;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * 通过 Servlet 请求、真实认证过滤器和 Spring Session 验证持久 Cookie 的恢复、轮换和撤销。
 * 只替换会话存储为隔离内存仓库，不连接业务数据库、Redis 或创建账号；生产使用同一 Cookie 自动配置。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootTest(classes = SessionPersistenceHttpTest.TestApplication.class,
        properties = "spring.profiles.active=local-mock")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SessionPersistenceHttpTest {
    private static final String PASSWORD = "session-fixture-password1";
    private static final Duration LIFETIME = Duration.ofDays(7);
    @Autowired MockMvc mvc;
    @Autowired MapSessionRepository sessions;
    @Autowired ObjectMapper json;
    @Autowired AuthenticationServiceImpl authentication;

    @Test
    void invalidTimeoutCannotTurnPersistentLoginIntoAnUnlimitedSession() {
        for (String value : List.of("0s", "-1s", "30000d")) {
            var environment = new org.springframework.mock.env.MockEnvironment()
                    .withProperty("spring.session.timeout", value);
            assertThatThrownBy(() -> authentication.configureSessionLifetime(environment))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void persistentCookieSurvivesClosingClientButLogoutAndCsrfRemainEffective() throws Exception {
        Browser first = browser();
        String csrf = csrf(first);
        send(first, "/api/v1/auth/captcha", null, null);
        String anonymous = cookie(first, "SESSION").getValue();
        // 旧会话可能在部署前创建；重新认证也必须应用当前有效期，而非继续旧的短 TTL。
        var old = sessions.findById(decode(anonymous));
        old.setMaxInactiveInterval(Duration.ofMinutes(10));
        sessions.save(old);
        var login = send(first, "/api/v1/auth/login", """
                {"identifier":"session-fixture@example.test","password":"%s","captcha":"ABCD"}
                """.formatted(PASSWORD), csrf);
        assertThat(login.getStatus()).isEqualTo(200);
        String header = login.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("SESSION=")).findFirst().orElseThrow();
        assertThat(header).contains("Max-Age=604800", "HttpOnly", "SameSite=Lax", "Path=/");
        HttpCookie authenticated = cookie(first, "SESSION");
        assertThat(authenticated.getValue()).isNotEqualTo(anonymous);
        assertThat(sessions.findById(decode(anonymous))).isNull();
        assertThat(sessions.findById(decode(authenticated.getValue())).getMaxInactiveInterval()).isEqualTo(LIFETIME);

        // 新客户端只恢复未过期的持久 Cookie，丢弃会话 Cookie及前端内存，模拟关闭浏览器后重新进入。
        Browser reopened = browser();
        for (HttpCookie value : first.cookies().getCookieStore().getCookies()) {
            if (value.getMaxAge() > 0) reopened.cookies().getCookieStore().add(uri("/"), value);
        }
        assertThat(send(reopened, "/api/v1/auth/me", null, null).getStatus()).isEqualTo(200);
        assertThat(send(reopened, "/api/v1/auth/logout", "{}", null).getStatus()).isEqualTo(403);
        assertThat(send(reopened, "/api/v1/admin/models", null, null).getStatus()).isEqualTo(403);
        var logout = send(reopened, "/api/v1/auth/logout", "{}", csrf(reopened));
        assertThat(logout.getStatus()).isEqualTo(200);
        assertThat(logout.getHeaders("Set-Cookie"))
                .anyMatch(value -> value.startsWith("SESSION=") && value.contains("Max-Age=0"));
        assertThat(sessions.findById(decode(authenticated.getValue()))).isNull();
        reopened.cookies().getCookieStore().add(uri("/"), authenticated);
        assertThat(send(reopened, "/api/v1/auth/me", null, null).getStatus()).isEqualTo(401);
    }

    @Test
    void serverExpiryRejectsAnOtherwiseUnexpiredPersistentCookie() throws Exception {
        Browser browser = browser();
        String csrf = csrf(browser);
        var response = send(browser, "/api/v1/auth/login", """
                {"identifier":"session-fixture@example.test","password":"%s","captcha":"ABCD"}
                """.formatted(PASSWORD), csrf);
        assertThat(response.getStatus()).isEqualTo(200);
        var stored = sessions.findById(decode(cookie(browser, "SESSION").getValue()));
        stored.setLastAccessedTime(Instant.now().minus(Duration.ofDays(8)));
        sessions.save(stored);
        assertThat(send(browser, "/api/v1/auth/me", null, null).getStatus()).isEqualTo(401);
    }

    private Browser browser() {
        return new Browser(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    private String csrf(Browser browser) throws Exception {
        var response = send(browser, "/api/v1/auth/csrf", null, null);
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).path("data").path("token").asText();
    }

    private HttpCookie cookie(Browser browser, String name) {
        return browser.cookies().getCookieStore().getCookies().stream()
                .filter(value -> value.getName().equals(name) && value.getMaxAge() != 0).findFirst().orElseThrow();
    }

    private MockHttpServletResponse send(Browser browser, String path, String body, String csrf) throws Exception {
        var request = body == null ? MockMvcRequestBuilders.get(path) : MockMvcRequestBuilders.post(path)
                .contentType("application/json").content(body);
        for (HttpCookie cookie : browser.cookies().getCookieStore().getCookies()) {
            if (!cookie.hasExpired()) request.cookie(new jakarta.servlet.http.Cookie(cookie.getName(), cookie.getValue()));
        }
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        var response = mvc.perform(request).andReturn().getResponse();
        browser.cookies().put(uri(path), Map.of("Set-Cookie", response.getHeaders("Set-Cookie")));
        return response;
    }

    private URI uri(String path) { return URI.create("http://localhost" + path); }
    private String decode(String value) { return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8); }
    private record Browser(CookieManager cookies) { }

    /** 装配真实认证和 Cookie 自动配置，仅以固定测试主体及本地仓库替代外部资源。 */
    @Configuration(proxyBeanMethods = false)
    @EnableSpringHttpSession
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class, SessionAutoConfiguration.class})
    @Import({AuthenticationController.class, AuthenticationServiceImpl.class, SecurityConfiguration.class,
            SecurityContextCurrentActor.class, SecurityErrorWriter.class})
    static class TestApplication {
        @Bean
        MapSessionRepository sessionRepository(SessionProperties properties) {
            var repository = new MapSessionRepository(new ConcurrentHashMap<>());
            repository.setDefaultMaxInactiveInterval(properties.getTimeout());
            return repository;
        }

        @Bean
        UserDetailsService users(PasswordEncoder encoder) {
            String hash = encoder.encode(PASSWORD);
            return name -> {
                if (!name.equals("session-fixture@example.test")) throw new UsernameNotFoundException("fixture missing");
                return new AuthenticatedUser(TestActors.USER_ID, TestActors.TENANT_ID, TestActors.WORKSPACE_ID,
                        name, "Session fixture", hash, "ACTIVE", List.of(new SimpleGrantedAuthority("ROLE_USER")));
            };
        }

        @Bean
        LoginCaptchaService captcha() {
            var captcha = mock(LoginCaptchaService.class);
            when(captcha.issue(any())).thenAnswer(call -> {
                ((jakarta.servlet.http.HttpServletRequest) call.getArgument(0)).getSession();
                return new byte[0];
            });
            return captcha;
        }
        @Bean LoginFailureGuard failureGuard() { return mock(LoginFailureGuard.class); }
        @Bean AnalyticsEventService analytics() { return mock(AnalyticsEventService.class); }
    }
}
