package com.promptoptimizer.identity.service;

import java.time.Duration;

/**
 * 向已规范化的邮箱投递一次性注册验证码。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface VerificationEmailSender {

    /** 向已验证格式的收件地址投递一次性验证码；邮件中不记录明文密码。 */
    void send(String recipient, String code, Duration validFor);
}
