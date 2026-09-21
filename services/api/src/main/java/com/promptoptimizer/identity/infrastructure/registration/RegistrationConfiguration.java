package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.application.RegistrationException;
import com.promptoptimizer.identity.application.VerificationEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

/**
 * 根据环境配置选择禁用、本地日志或 SMTP 邮件发送器。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegistrationProperties.class)
public class RegistrationConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "app.security.registration.delivery-mode", havingValue = "smtp")
    VerificationEmailSender smtpVerificationEmailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            RegistrationProperties properties
    ) {
        return (recipient, code, validFor) -> {
            JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
            if (mailSender == null || properties.getFromAddress().isBlank()) {
                throw deliveryUnavailable();
            }
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.getFromAddress().trim());
            message.setTo(recipient);
            message.setSubject("Prompt Optimizer 注册验证码");
            message.setText("你的注册验证码是：" + code + "\n\n验证码将在 "
                    + validFor.toMinutes() + " 分钟后失效。如非本人操作，请忽略此邮件。");
            try {
                mailSender.send(message);
            } catch (MailException exception) {
                LOGGER.warn("注册验证码邮件投递失败；原因类型：{}", exception.getClass().getSimpleName());
                throw deliveryUnavailable();
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.registration.delivery-mode", havingValue = "log")
    VerificationEmailSender loggingVerificationEmailSender() {
        return (recipient, code, validFor) -> LOGGER.warn(
                "仅限本地开发：邮箱 {} 的注册验证码为 {}，有效期 {} 秒",
                recipient,
                code,
                validFor.toSeconds()
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.security.registration.delivery-mode",
            havingValue = "disabled",
            matchIfMissing = true
    )
    VerificationEmailSender disabledVerificationEmailSender() {
        return (recipient, code, validFor) -> {
            throw deliveryUnavailable();
        };
    }

    private static RegistrationException deliveryUnavailable() {
        return new RegistrationException(
                RegistrationException.Reason.DELIVERY_UNAVAILABLE,
                "验证码邮件暂时无法发送，请稍后重试。"
        );
    }
}
