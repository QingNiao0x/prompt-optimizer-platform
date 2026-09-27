package com.promptoptimizer.identity.service;

import com.promptoptimizer.identity.dto.AuthenticatedUserView;
import com.promptoptimizer.identity.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 登录、当前用户读取和退出的应用服务边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface AuthenticationService {

    /**
     * 校验邮箱或用户名密码、轮换会话标识并显式保存认证上下文。
     */
    AuthenticatedUserView login(
            LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response
    );

    /** 邮箱验证码注册成功后建立会话，不再要求图形验证码。 */
    AuthenticatedUserView loginAfterRegistration(
            String identifier,
            String password,
            HttpServletRequest request,
            HttpServletResponse response
    );

    /** 由服务端认证上下文读取当前用户，不接受客户端自报身份。 */
    AuthenticatedUserView currentUser();

    /** 使当前 HttpSession 失效并清除 CSRF Cookie；退出后必须重新登录。 */
    void logout(HttpServletRequest request, HttpServletResponse response);
}
