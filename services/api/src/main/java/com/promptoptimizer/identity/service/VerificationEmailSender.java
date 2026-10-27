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

    /** 仅已配置的真实绑定投递器返回true，日志模式不能作为邮箱所有权验证。 */
    default boolean supportsBinding() { return false; }

    /** 绑定验证码必须使用独立用途文案；未声明支持的开发投递器拒绝发送。 */
    default void sendBinding(String recipient, String code, Duration validFor) {
        throw new RegistrationException(RegistrationException.Reason.DELIVERY_UNAVAILABLE,
                "邮箱绑定邮件暂时无法发送，请稍后重试。");
    }
}
