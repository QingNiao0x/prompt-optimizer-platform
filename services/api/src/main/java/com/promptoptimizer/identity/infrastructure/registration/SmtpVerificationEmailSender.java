package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.service.RegistrationException;
import com.promptoptimizer.identity.service.VerificationEmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 通过配置的 SMTP 服务投递注册验证码，使用 UTF-8 产品署名及项目内的纯文本模板。
 * 不读取云控制台模板、不自动重试发送，不记录收件人、验证码或上游原始异常。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class SmtpVerificationEmailSender implements VerificationEmailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpVerificationEmailSender.class);
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final RegistrationProperties properties;

    /** 保留延迟获取 SMTP 客户端的行为，缺少邮件配置不会影响密码登录等其他功能。 */
    public SmtpVerificationEmailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                                       RegistrationProperties properties) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
    }

    /** 仅使用注册模板发送一次验证码；失败返回稳定错误，不将邮件对象附在业务异常中。 */
    @Override
    public void send(String recipient, String code, Duration validFor) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw configurationUnavailable("SMTP_CLIENT_MISSING");
        }
        if (properties.getFromAddress() == null || properties.getFromAddress().isBlank()) {
            throw configurationUnavailable("FROM_ADDRESS_MISSING");
        }
        validateClientConfiguration(mailSender);
        VerificationEmailTemplate.EmailContent content = VerificationEmailTemplate.REGISTRATION.render(code, validFor);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // 同时防范自定义 JavaMailSender 的 Session 绕过 Spring Mail 属性检查。
            if (message.getSession().getDebug()
                    || Boolean.parseBoolean(message.getSession().getProperty("mail.debug.auth"))) {
                throw configurationUnavailable("SESSION_DEBUG_ENABLED");
            }
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setValidateAddresses(true);
            helper.setFrom(properties.getFromAddress().trim(), properties.getFromName());
            helper.setTo(recipient);
            helper.setSubject(content.subject());
            helper.setText(content.body(), false);
            mailSender.send(message);
        } catch (MailException | MessagingException | UnsupportedEncodingException exception) {
            // 邮件异常可能持有密码、收件地址和完整 MimeMessage，仅记录有限的异常类型。
            LOGGER.warn("event=email.delivery.failed purpose=REGISTER failureType={}", exception.getClass().getSimpleName());
            throw deliveryUnavailable();
        }
    }

    /** 在网络发送前识别缺失的运行时配置；保留自定义客户端及免认证 SMTP 的既有兼容性。 */
    private static void validateClientConfiguration(JavaMailSender mailSender) {
        if (!(mailSender instanceof JavaMailSenderImpl client)) {
            return;
        }
        String protocol = client.getProtocol() == null ? "smtp" : client.getProtocol();
        String host = client.getHost();
        if (host == null || host.isBlank()) {
            // 保留 JNDI/自定义 Session 的主机配置，不能仅因 JavaMailSenderImpl 字段为空而误拒绝。
            host = client.getSession().getProperty("mail." + protocol + ".host");
        }
        if (host == null || host.isBlank()) {
            throw configurationUnavailable("SMTP_HOST_MISSING");
        }
        // 只对明确要求账号认证的客户端检查凭据，不能影响原有匿名 SMTP 或自定义投递适配器。
        if (Boolean.parseBoolean(client.getJavaMailProperties().getProperty("mail." + protocol + ".auth"))) {
            if (client.getUsername() == null || client.getUsername().isBlank()) {
                throw configurationUnavailable("SMTP_USERNAME_MISSING");
            }
            if (client.getPassword() == null || client.getPassword().isBlank()) {
                throw configurationUnavailable("SMTP_PASSWORD_MISSING");
            }
        }
    }

    /** 仅记录代码内固定原因码，不暴露地址、密钥值或供应商原始响应。 */
    private static RegistrationException configurationUnavailable(String reason) {
        LOGGER.warn("event=email.delivery.failed purpose=REGISTER reason={}", reason);
        return deliveryUnavailable();
    }

    /** 供应商原始错误不进入业务响应，避免泄露发信账号或验证内容。 */
    private static RegistrationException deliveryUnavailable() {
        return new RegistrationException(RegistrationException.Reason.DELIVERY_UNAVAILABLE,
                "验证码邮件暂时无法发送，请稍后重试。");
    }
}
