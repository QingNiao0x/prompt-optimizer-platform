package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.settings.AccountSessionValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * 在授权和业务执行前校验会话快照；数据库故障失败关闭，模拟模式由独立配置显式跳过。
 * 本过滤器仅装入SecurityFilterChain，避免Servlet重复注册。
 * @author QingNiao
 * @since 0.1.0
 */
public final class AccountSessionFilter extends OncePerRequestFilter {
    private final AccountSessionValidator validator;
    private final SecurityErrorWriter errors;
    private final org.springframework.security.web.context.SecurityContextRepository contexts;
    public AccountSessionFilter(AccountSessionValidator validator, SecurityErrorWriter errors,
            org.springframework.security.web.context.SecurityContextRepository contexts) {
        this.validator = validator;
        this.errors = errors;
        this.contexts = contexts;
    }

    /** 匿名请求保持原白名单与CSRF流程；已认证会话必须通过权威状态检查。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (validator != null && authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            final AuthenticatedUser refreshed;
            try { refreshed = validator.refresh(principal); }
            catch (RuntimeException exception) {
                errors.write(request, response, 503, "SESSION_VALIDATION_UNAVAILABLE", "登录状态校验暂时不可用，请稍后重试。");
                return;
            }
            if (refreshed == null) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                errors.write(request, response, 401, "SESSION_REVOKED", "登录状态已失效，请重新登录。");
                return;
            }
            if (refreshed != principal) {
                SecurityContextHolder.getContext().setAuthentication(
                        org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                                refreshed, null, refreshed.getAuthorities()));
                contexts.saveContext(SecurityContextHolder.getContext(), request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
