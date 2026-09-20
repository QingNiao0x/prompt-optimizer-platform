package com.promptoptimizer.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

import java.util.List;

/**
 * 浏览器最小认证配置：安全 Session、HttpOnly 会话 Cookie 与 Cookie/Header CSRF 双提交。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SecurityErrorWriter errorWriter
    ) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");

        http
                .securityContext(context -> context
                        .requireExplicitSave(true)
                        .securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/login",
                                "/api/v1/health",
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                                request,
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED",
                                "请先登录后再继续操作。"
                        ))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                request,
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                "ACCESS_DENIED",
                                "当前用户无权执行该操作。"
                        )));

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)
        ));
    }
}
