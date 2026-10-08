package com.promptoptimizer.identity.service.impl;

import com.promptoptimizer.identity.service.AuthenticationService;
import com.promptoptimizer.identity.service.LoginCaptchaService;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
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
public class AuthenticationServiceImpl implements AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;
    private final CurrentActor currentActor;
    private final AnalyticsEventService analyticsEventService;
    private final LoginCaptchaService loginCaptchaService;
    private final LoginFailureGuard loginFailureGuard;
    private com.promptoptimizer.identity.mapper.SmsAccountMapper smsAccounts;

    /** 本地模拟模式没有持久化 Mapper；真实账户资料只暴露脱敏号码。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void configurePhoneProjection(org.springframework.beans.factory.ObjectProvider<com.promptoptimizer.identity.mapper.SmsAccountMapper> accounts,
            org.springframework.core.env.Environment environment) {
        smsAccounts = java.util.Arrays.asList(environment.getActiveProfiles()).contains("local-mock") ? null : accounts.getIfAvailable();
    }

    public AuthenticationServiceImpl(
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
        // 显式 PHONE 才解析手机号；旧请求中的数字用户名继续按原行为解析。
        if (loginRequest.identityType() == com.promptoptimizer.identity.domain.UserIdentityType.PHONE) {
            identifier = "PHONE:" + com.promptoptimizer.identity.domain.MainlandPhone.normalize(identifier);
        } else if (loginRequest.identityType() != null
                && loginRequest.identityType() != com.promptoptimizer.identity.domain.UserIdentityType.EMAIL
                && loginRequest.identityType() != com.promptoptimizer.identity.domain.UserIdentityType.USERNAME) {
            throw new BadCredentialsException("不支持的密码登录类型");
        }
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
        try {
            loginFailureGuard.recordSuccess(identifier);
        } catch (org.springframework.dao.DataAccessException | org.springframework.security.authentication.AuthenticationServiceException exception) {
            // 认证前的限流已经通过；清理失败只保留更严格的旧计数，不能把已建号误报为注册失败。
            org.slf4j.LoggerFactory.getLogger(AuthenticationServiceImpl.class)
                    .warn("event=auth.failure_counter_cleanup_deferred type={}", exception.getClass().getSimpleName());
        }
        return establishSession(authentication, request, response);
    }

    /** 短信只交给独立 Provider；不伪造密码，也不清除密码失败预算。 */
    @Override public AuthenticatedUserView loginWithSms(com.promptoptimizer.identity.dto.SmsRequests.Login credentials,
            HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                new com.promptoptimizer.identity.security.SmsAuthenticationToken(credentials, request));
        return establishSession(authentication, request, response);
    }

    /** 两条认证链路共享 Session ID 轮换、CSRF 更新及上下文保存。 */
    private AuthenticatedUserView establishSession(Authentication authentication, HttpServletRequest request,
            HttpServletResponse response) {
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
        var actor = currentActor.require();
        var phone = smsAccounts == null ? null : smsAccounts.activePhone(actor.userId());
        return AuthenticatedUserView.from(actor, platformAdmin).withPhone(phone == null ? null
                : com.promptoptimizer.identity.domain.MainlandPhone.masked(phone.getNormalizedIdentifier()));
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
