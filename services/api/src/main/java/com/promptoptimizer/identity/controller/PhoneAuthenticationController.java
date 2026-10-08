package com.promptoptimizer.identity.controller;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.domain.MainlandPhone;
import com.promptoptimizer.identity.domain.SmsPurpose;
import com.promptoptimizer.identity.dto.AuthenticatedUserView;
import com.promptoptimizer.identity.dto.SmsRequests;
import com.promptoptimizer.identity.infrastructure.sms.SmsProperties;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import com.promptoptimizer.identity.service.AuthenticationService;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.identity.service.SmsException;
import com.promptoptimizer.identity.service.impl.PhoneVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 手机认证协议入口；绑定目标只从服务端身份取得，所有写请求继续由 Security 校验 CSRF。
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1")
public class PhoneAuthenticationController {
    private final ObjectProvider<PhoneVerificationService> phones;
    private final AuthenticationService authentication;
    private final CurrentActor actor;
    private final SmsProperties properties;

    public PhoneAuthenticationController(ObjectProvider<PhoneVerificationService> phones, AuthenticationService authentication,
            CurrentActor actor, SmsProperties properties) {
        this.phones = phones; this.authentication = authentication; this.actor = actor; this.properties = properties;
    }

    /** 只公开可用入口，不返回供应商、签名、方案名或密钥配置。 */
    @GetMapping("/auth/capabilities")
    public ApiResponse<SmsRequests.Capabilities> capabilities(HttpServletRequest request) {
        boolean enabled = properties.isEnabled();
        return response(request, new SmsRequests.Capabilities(enabled, enabled, enabled));
    }

    /** 匿名发码仅允许注册和登录用途；绑定必须经已登录的专用入口。 */
    @PostMapping("/auth/sms/challenges")
    public ApiResponse<SmsRequests.ChallengeView> challenge(@Valid @RequestBody SmsRequests.Challenge body, HttpServletRequest request) {
        if (body.purpose() == SmsPurpose.BIND) throw SmsException.invalid();
        return response(request, service().issue(body.phone(), body.purpose(), body.captcha(), null, null, request));
    }

    /** 手机注册建立全新的个人账户；重试只恢复已提交结果，仍需校验该账户的真实密码。 */
    @PostMapping("/auth/phone/register")
    public ApiResponse<AuthenticatedUserView> register(@Valid @RequestBody SmsRequests.Registration body,
            HttpServletRequest request, HttpServletResponse response) {
        service().register(body, request);
        return response(request, authentication.loginAfterRegistration("PHONE:" + MainlandPhone.normalize(body.phone()),
                body.password(), request, response));
    }

    /** 短信登录不自动注册、不允许管理员单因素登录。 */
    @PostMapping("/auth/phone/login")
    public ApiResponse<AuthenticatedUserView> login(@Valid @RequestBody SmsRequests.Login body,
            HttpServletRequest request, HttpServletResponse response) {
        service();
        return response(request, authentication.loginWithSms(body, request, response));
    }

    /** 绑定发码前进行当前密码重新认证，并限制当前用户的发送预算。 */
    @PostMapping("/me/phone-binding/challenges")
    public ApiResponse<SmsRequests.ChallengeView> bindingChallenge(@Valid @RequestBody SmsRequests.BindingChallenge body,
            HttpServletRequest request) {
        return response(request, service().issue(body.phone(), SmsPurpose.BIND, body.captcha(), principal(), body.currentPassword(), request));
    }

    /** 只完成首次绑定，冲突不修改任意一方的账户、工作区或历史。 */
    @PostMapping("/me/phone-binding")
    public ApiResponse<AuthenticatedUserView> bind(@Valid @RequestBody SmsRequests.Binding body, HttpServletRequest request) {
        service().bind(body, principal(), request);
        return response(request, authentication.currentUser());
    }

    /** 开关关闭时拒绝直接调用隐藏的入口，不以缺失 Bean 产生内部错误。 */
    private PhoneVerificationService service() {
        PhoneVerificationService service = phones.getIfAvailable();
        if (!properties.isEnabled() || service == null) throw SmsException.unavailable();
        return service;
    }

    /** 拒绝匿名、旧会话缺失登录身份或客户端伪造的绑定目标。 */
    private AuthenticatedUser principal() {
        var current = actor.require();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)
                || !user.actorIdentity().userId().equals(current.userId())) throw new BadCredentialsException("请重新登录后绑定。");
        return user;
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T body) {
        return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), body);
    }
}
