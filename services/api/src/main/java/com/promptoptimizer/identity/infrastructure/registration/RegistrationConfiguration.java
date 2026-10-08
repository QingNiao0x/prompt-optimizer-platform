package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.service.RegistrationException;
import com.promptoptimizer.identity.service.VerificationEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Arrays;

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

    /** SMTP 只替换邮件投递适配器，不改变验证码、账户创建或 Session 规则。 */
    @Bean
    @ConditionalOnProperty(name = "app.security.registration.delivery-mode", havingValue = "smtp")
    VerificationEmailSender smtpVerificationEmailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            RegistrationProperties properties,
            Environment environment
    ) {
        validateSmtpPrivacy(environment);
        return new SmtpVerificationEmailSender(mailSenderProvider, properties);
    }

    /** 真实邮件拒绝会输出身份 SQL 或完整 SMTP 会话的诊断模式，不静默改变全局日志设置。 */
    private static void validateSmtpPrivacy(Environment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("local-sql-debug")
                || Arrays.asList(environment.getActiveProfiles()).contains("local-mock")
                || environment.getProperty("decorator.datasource.enabled", Boolean.class, false)
                || environment.getProperty("spring.datasource.url", "").contains(":p6spy:")
                || environment.getProperty("mybatis-plus.configuration.log-impl", "").contains("StdOutImpl")) {
            throw new IllegalStateException("真实 SMTP 邮件不能与 local-sql-debug、local-mock 或 SQL 参数打印同时启用。");
        }
        if (environment.getProperty("spring.mail.properties.mail.debug", Boolean.class, false)
                || environment.getProperty("spring.mail.properties.mail.debug.auth", Boolean.class, false)) {
            throw new IllegalStateException("真实 SMTP 邮件禁止开启 mail.debug / mail.debug.auth，避免泄露验证码和认证信息。");
        }
        LoggingSystem logging = LoggingSystem.get(RegistrationConfiguration.class.getClassLoader());
        // 语句级 Logger 能覆盖包级配置，因此核对实际生效的 Logger，不能仅查看配置文件。
        for (var configuration : logging.getLoggerConfigurations()) {
            String name = configuration.getName();
            boolean sensitiveLogger = name.equals(LoggingSystem.ROOT_LOGGER_NAME)
                    || name.startsWith("com.promptoptimizer.identity")
                    || name.startsWith("org.springframework.mail")
                    || name.startsWith("org.eclipse.angus.mail")
                    || name.startsWith("com.sun.mail")
                    || name.startsWith("jakarta.mail")
                    || name.startsWith("p6spy");
            if (sensitiveLogger && (configuration.getEffectiveLevel() == LogLevel.DEBUG
                    || configuration.getEffectiveLevel() == LogLevel.TRACE)) {
                throw new IllegalStateException("真实 SMTP 邮件禁止身份 Mapper、邮件客户端或根 Logger 的 DEBUG/TRACE 日志。");
            }
        }
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
