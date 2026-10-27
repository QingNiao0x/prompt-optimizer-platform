package com.promptoptimizer.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.settings.AccountSessionValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import jakarta.servlet.FilterChain;

import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 过滤器在状态存储故障时失败关闭，旧版本拒绝不会继续执行业务链；显式Mock配置保持原行为。
 * @author QingNiao
 * @since 0.1.0
 */
class AccountSessionFilterTest {
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void unavailableStateStoreStopsBusinessWithSanitized503() throws Exception {
        var validator = mock(AccountSessionValidator.class);
        var principal = principal();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        when(validator.refresh(principal)).thenThrow(new IllegalStateException("private-database-details"));
        var filter = new AccountSessionFilter(validator, new SecurityErrorWriter(new ObjectMapper()), mock(SecurityContextRepository.class));
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        filter.doFilter(new MockHttpServletRequest(), response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SESSION_VALIDATION_UNAVAILABLE").doesNotContain("private-database-details");
        verifyNoInteractions(chain);
    }

    @Test
    void revokedSessionIsInvalidatedBeforeBusinessAndMockHasExplicitBypass() throws Exception {
        var validator = mock(AccountSessionValidator.class);
        var principal = principal();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var request = new MockHttpServletRequest(); request.getSession();
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        new AccountSessionFilter(validator, new SecurityErrorWriter(new ObjectMapper()), mock(SecurityContextRepository.class))
                .doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
        var mockRequest = new MockHttpServletRequest(); var mockResponse = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        new AccountSessionFilter(null, new SecurityErrorWriter(new ObjectMapper()), mock(SecurityContextRepository.class))
                .doFilter(mockRequest, mockResponse, chain);
        verify(chain).doFilter(mockRequest, mockResponse);
    }

    private static AuthenticatedUser principal() {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "synthetic@example.test",
                "synthetic", "模拟用户", "", "ACTIVE", List.of(new SimpleGrantedAuthority("ROLE_USER")), UUID.randomUUID(), 0L);
    }
}
