package com.promptoptimizer.identity.service.impl;

import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.analytics.service.impl.AnalyticsEventService;
import com.promptoptimizer.identity.dto.AuthenticatedUserView;
import com.promptoptimizer.identity.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 登录、当前用户读取和退出的应用模块。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;
    private final CurrentActor currentActor;
    private final AnalyticsEventService analyticsEventService;
    private final LoginCaptchaService loginCaptchaService;
    private final LoginFailureGuard loginFailureGuard;

    public AuthenticationService(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CsrfTokenRepository csrfTokenRepository,
            CurrentActor currentActor,
            AnalyticsEventService analyticsEventService,
            LoginCaptchaService loginCaptchaService,
            LoginFailureGuard loginFailureGuard
    ) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
        this.currentActor = currentActor;
        this.analyticsEventService = analyticsEventService;
        this.loginCaptchaService = loginCaptchaService;
        this.loginFailureGuard = loginFailureGuard;
    }

    /**
     * 校验邮箱或用户名密码、轮换会话标识并显式保存认证上下文。
     */
    public AuthenticatedUserView login(
            LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (loginRequest.password().length() < 8
                || loginRequest.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("无效凭据");
        }
        String identifier = loginRequest.identifier().trim();
        String clientAddress = request.getRemoteAddr();
        loginFailureGuard.checkAllowed(identifier, clientAddress);
        loginCaptchaService.verifyAndConsume(request, loginRequest.captcha());
        return completeLogin(identifier, loginRequest.password(), clientAddress, request, response);
    }

    /** 邮箱验证码注册成功后建立会话，不再要求图形验证码。 */
    public AuthenticatedUserView loginAfterRegistration(
            String identifier,
            String password,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String clientAddress = request.getRemoteAddr();
        loginFailureGuard.checkAllowed(identifier, clientAddress);
        return completeLogin(identifier, password, clientAddress, request, response);
    }

    private AuthenticatedUserView completeLogin(
            String identifier,
            String password,
            String clientAddress,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(identifier, password)
            );
        } catch (BadCredentialsException exception) {
            loginFailureGuard.recordFailure(identifier, clientAddress);
            throw exception;
        }
        loginFailureGuard.recordSuccess(identifier);
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        // 登录时旧 Token 已被旋转策略清除；立即下发新 Token，保证下一次写请求可用。
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        analyticsEventService.recordLogin(request);
        return currentUser();
    }

    /**
     * 由服务端认证上下文读取当前用户，不接受客户端自报身份。
     */
    public AuthenticatedUserView currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean platformAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_PLATFORM_ADMIN".equals(authority.getAuthority()));
        return AuthenticatedUserView.from(currentActor.require(), platformAdmin);
    }

    /**
     * 使当前 HttpSession 失效并清除 CSRF Cookie；退出后必须重新登录。
     */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        analyticsEventService.recordLogout(request);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        csrfTokenRepository.saveToken(null, request, response);
    }
}
