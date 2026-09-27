package com.promptoptimizer.identity.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 登录图形验证码的签发与一次性校验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface LoginCaptchaService {

    /** 会话中保存验证码答案的属性名。 */
    String ATTRIBUTE = "LOGIN_CAPTCHA";

    /** 签发验证码图片，答案只保存在服务端会话。 */
    byte[] issue(HttpServletRequest request);

    /** 校验并消费本次验证码；失败时拒绝继续登录。 */
    void verifyAndConsume(HttpServletRequest request, String submitted);
}
