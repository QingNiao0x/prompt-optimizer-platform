package com.promptoptimizer.identity.infrastructure.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.exception.GlobalExceptionHandler;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.controller.RegistrationController;
import com.promptoptimizer.identity.security.SecurityConfiguration;
import com.promptoptimizer.identity.security.SecurityErrorWriter;
import com.promptoptimizer.identity.service.AuthenticationService;
import com.promptoptimizer.identity.service.EmailRegistrationService;
import com.promptoptimizer.identity.service.impl.EmailRegistrationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 隔离启动真实 HTTP/Security，复现 local-mock 缺失注册路由被误报为非业务错误的问题。
 * 不加载业务数据库、Redis、SMTP 或 PNVS，也不创建账户；完整模式另有注册/SMTP 回归。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootTest(classes = LocalMockRegistrationHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=local-mock",
        "app.security.registration.delivery-mode=disabled",
        "app.security.sms.enabled=false",
        "logging.level.root=INFO"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalMockRegistrationHttpTest {

    @LocalServerPort
    private int port;

    @MockBean
    private AuthenticationService authenticationService;

    @Test
    void shouldExplainUnavailableRegistrationInsteadOfReportingMissingAuthentication() throws Exception {
        HttpClient client = client();
        String token = csrf(client);
        HttpResponse<String> response = post(client, "/api/v1/auth/registration-code",
                "{\"email\":\"local-fixture@example.test\"}", token);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(new ObjectMapper().readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("REGISTRATION_SERVICE_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("local-fixture@example.test", "AUTHENTICATION_REQUIRED");
        verifyNoInteractions(authenticationService);
    }

    @Test
    void shouldNotCreateMockAccountsOrBypassCsrf() throws Exception {
        HttpClient client = client();
        HttpResponse<String> denied = post(client, "/api/v1/auth/registration-code",
                "{\"email\":\"local-fixture@example.test\"}", null);
        assertThat(denied.statusCode()).isEqualTo(403);

        HttpResponse<String> unavailable = post(client, "/api/v1/auth/register", """
                {"email":"local-fixture@example.test","verificationCode":"012345","password":"synthetic-password1"}
                """, csrf(client));
        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(new ObjectMapper().readTree(unavailable.body()).path("error").path("code").asText())
                .isEqualTo("REGISTRATION_SERVICE_UNAVAILABLE");
        verifyNoInteractions(authenticationService);
    }

    /** 使用独立 Cookie 容器，实际验证 Cookie/Header 双提交而非测试注入的 CSRF。 */
    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    private String csrf(HttpClient client) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return new ObjectMapper().readTree(response.body()).path("token").asText();
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("X-XSRF-TOKEN", token);
        }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** 仅装配本次 HTTP 链路，避免 Mock profile 的数据库自动配置影响或污染开发实例。 */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            ValidationAutoConfiguration.class, SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    @Import({RegistrationController.class, RegistrationConfiguration.class, GlobalExceptionHandler.class,
            RequestIdFilter.class, SecurityConfiguration.class, SecurityErrorWriter.class, CsrfEndpoint.class})
    @ComponentScan(basePackageClasses = EmailRegistrationServiceImpl.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = EmailRegistrationService.class))
    static class TestApplication {
        @Bean
        UserDetailsService userDetailsService() {
            return name -> User.withUsername("local-fixture").password("{noop}unused-fixture1").roles("USER").build();
        }
    }

    /** 仅令真实安全过滤器生成 CSRF Cookie；无需装配账户读取、短信或认证业务。 */
    @RestController
    static class CsrfEndpoint {
        @GetMapping("/api/v1/auth/csrf")
        Map<String, String> csrf(CsrfToken token) {
            return Map.of("token", token.getToken());
        }
    }
}
