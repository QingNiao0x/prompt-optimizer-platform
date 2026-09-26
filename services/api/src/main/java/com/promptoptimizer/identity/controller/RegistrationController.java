package com.promptoptimizer.identity.controller;

import com.promptoptimizer.identity.dto.AuthenticatedUserView;
import com.promptoptimizer.identity.dto.EmailRegistrationCodeRequest;
import com.promptoptimizer.identity.dto.EmailRegistrationCodeView;
import com.promptoptimizer.identity.dto.EmailRegistrationRequest;
import com.promptoptimizer.identity.dto.LoginRequest;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.AuthenticationService;
import com.promptoptimizer.identity.service.EmailRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无需预先登录的邮箱注册接口；仍受 CSRF 保护。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/auth")
@Profile("!local-mock")
public class RegistrationController {

    private final EmailRegistrationService registrationService;
    private final AuthenticationService authenticationService;

    public RegistrationController(
            EmailRegistrationService registrationService,
            AuthenticationService authenticationService
    ) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
    }

    /** 在限流规则内发送注册验证码，响应中不返回验证码正文。 */
    @PostMapping("/registration-code")
    public ApiResponse<EmailRegistrationCodeView> requestRegistrationCode(
            @Valid @RequestBody EmailRegistrationCodeRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                requestId(request),
                registrationService.requestCode(body, request.getRemoteAddr())
        );
    }

    /** 校验验证码并创建个人账户，成功后建立登录会话。 */
    @PostMapping("/register")
    public ApiResponse<AuthenticatedUserView> register(
            @Valid @RequestBody EmailRegistrationRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        EmailRegistrationService.RegisteredEmail registered = registrationService.register(body);
        return ApiResponse.success(
                requestId(request),
                authenticationService.loginAfterRegistration(registered.email(), body.password(), request, response)
        );
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
