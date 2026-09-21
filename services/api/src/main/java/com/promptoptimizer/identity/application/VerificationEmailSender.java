package com.promptoptimizer.identity.application;

import java.time.Duration;

/**
 * 向已规范化的邮箱投递一次性注册验证码。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface VerificationEmailSender {

    void send(String recipient, String code, Duration validFor);
}
