package com.promptoptimizer.identity.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.application.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 最小浏览器认证接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * 生成可由 Axios 通过 Cookie/Header 双提交的 CSRF Token。
     */
    @GetMapping("/csrf")
    public ApiResponse<CsrfTokenView> csrf(CsrfToken csrfToken, HttpServletRequest request) {
        return ApiResponse.success(requestId(request), CsrfTokenView.from(csrfToken));
    }

    /** 验证邮箱与密码，建立浏览器登录会话。 */
    @PostMapping("/login")
    public ApiResponse<AuthenticatedUserView> login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return ApiResponse.success(
                requestId(request),
                authenticationService.login(loginRequest, request, response)
        );
    }

    /** 返回当前已认证用户的公开资料，不包含密码或密钥。 */
    @GetMapping("/me")
    public ApiResponse<AuthenticatedUserView> me(HttpServletRequest request) {
        return ApiResponse.success(requestId(request), authenticationService.currentUser());
    }

    /** 注销当前浏览器会话并清除认证状态。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authenticationService.logout(request, response);
        return ApiResponse.success(requestId(request), null);
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
