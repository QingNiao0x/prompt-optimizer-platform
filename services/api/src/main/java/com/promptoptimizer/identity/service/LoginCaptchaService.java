package com.promptoptimizer.identity.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 登录图形验证码的签发与一次性校验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface LoginCaptchaService {

    /** 服务端会话内的图形答案属性；原子消费还需独立挑战编号，不作为公开接口返回。 */
    String ATTRIBUTE = "LOGIN_CAPTCHA";

    /** 签发五分钟有效的验证码图片，答案只在服务端，Redis负责原子消费。 */
    byte[] issue(HttpServletRequest request);

    /** 校验并消费本次验证码；失败时拒绝继续登录。 */
    void verifyAndConsume(HttpServletRequest request, String submitted);
}
