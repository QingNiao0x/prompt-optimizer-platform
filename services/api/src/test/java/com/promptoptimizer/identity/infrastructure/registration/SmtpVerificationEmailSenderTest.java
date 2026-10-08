package com.promptoptimizer.identity.infrastructure.registration;

import com.promptoptimizer.identity.service.RegistrationException;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模拟 SMTP 投递，断言真实 MIME 内容及安全失败行为；不会建立网络连接或发信。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@ExtendWith(OutputCaptureExtension.class)
class SmtpVerificationEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final RegistrationProperties properties = new RegistrationProperties();
    private final Session session = Session.getInstance(new Properties());

    @BeforeEach
    void setUp() {
        properties.setFromAddress(" noreply@example.test ");
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(session));
    }

    @Test
    void shouldSendUtf8RegistrationTemplateWhenConfigured(CapturedOutput output) throws Exception {
        sender(true).send("recipient@example.test", "012345", Duration.ofMinutes(5));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("noreply@example.test");
        assertThat(from.getPersonal()).isEqualTo("PromptOptimizer");
        assertThat(message.getRecipients(Message.RecipientType.TO)).hasSize(1);
        assertThat(((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress())
                .isEqualTo("recipient@example.test");
        assertThat(message.getSubject()).isEqualTo("【PromptOptimizer】邮箱注册验证码");
        assertThat(message.getContent()).isEqualTo(
                VerificationEmailTemplate.REGISTRATION.render("012345", Duration.ofMinutes(5)).body());
        assertThat(message.getContentType()).contains("text/plain", "UTF-8");
        assertThat(output.getAll()).doesNotContain("012345", "recipient@example.test", "noreply@example.test");
    }

    @Test
    void shouldPreserveConfiguredChineseDisplayNameWhenSending() throws Exception {
        properties.setFromName("提示词优化工具");
        sender(true).send("recipient@example.test", "012345", Duration.ofMinutes(5));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(((InternetAddress) captor.getValue().getFrom()[0]).getPersonal()).isEqualTo("提示词优化工具");
    }

    @Test
    void shouldReportUnavailableWhenMailClientIsMissing() {
        assertDeliveryUnavailable(() -> sender(false).send("recipient@example.test", "012345", Duration.ofMinutes(5)));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldReportUnavailableWhenFromAddressIsMissing(String address) {
        properties.setFromAddress(address);

        assertDeliveryUnavailable(() -> sender(true).send("recipient@example.test", "012345", Duration.ofMinutes(5)));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void shouldSanitizeAuthenticationFailureWhenProviderRejectsPassword(CapturedOutput output) {
        doThrow(new MailAuthenticationException("private-smtp-password recipient@example.test 012345"))
                .when(mailSender).send(any(MimeMessage.class));

        assertDeliveryUnavailable(() -> sender(true).send("recipient@example.test", "012345", Duration.ofMinutes(5)));
        assertThat(output.getAll()).contains("event=email.delivery.failed", "purpose=REGISTER", "MailAuthenticationException")
                .doesNotContain("private-smtp-password", "recipient@example.test", "012345");
    }

    @Test
    void shouldNotRetryWhenSmtpSendFails(CapturedOutput output) {
        doThrow(new MailSendException("SMTP timeout recipient@example.test 012345"))
                .when(mailSender).send(any(MimeMessage.class));

        assertDeliveryUnavailable(() -> sender(true).send("recipient@example.test", "012345", Duration.ofMinutes(5)));
        verify(mailSender).send(any(MimeMessage.class));
        assertThat(output.getAll()).doesNotContain("recipient@example.test", "012345", "SMTP timeout");
    }

    @Test
    void shouldRejectInvalidRecipientBeforeSend(CapturedOutput output) {
        assertDeliveryUnavailable(() -> sender(true).send("not-an-email", "012345", Duration.ofMinutes(5)));

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(output.getAll()).doesNotContain("not-an-email", "012345");
    }

    @Test
    void shouldRejectCustomSessionDebugBeforeSend(CapturedOutput output) {
        session.setDebug(true);

        assertDeliveryUnavailable(() -> sender(true).send("recipient@example.test", "012345", Duration.ofMinutes(5)));
        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(output.getAll()).doesNotContain("recipient@example.test", "012345");
    }

    @Test
    void shouldRejectCustomSessionAuthDebugBeforeSend() {
        session.getProperties().setProperty("mail.debug.auth", "true");

        assertDeliveryUnavailable(() -> sender(true).send("recipient@example.test", "012345", Duration.ofMinutes(5)));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    /** 用可空客户端的本地 Bean 容器验证惰性配置，不创建真实 SMTP 客户端。 */
    private SmtpVerificationEmailSender sender(boolean configured) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (configured) {
            factory.addBean("mailSender", mailSender);
        }
        return new SmtpVerificationEmailSender(factory.getBeanProvider(JavaMailSender.class), properties);
    }

    /** 业务异常既不携带云端原始错误，也不携带 MimeMessage 或验证码。 */
    private static void assertDeliveryUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(RegistrationException.class)
                .hasMessage("验证码邮件暂时无法发送，请稍后重试。")
                .hasNoCause()
                .extracting(exception -> ((RegistrationException) exception).getReason())
                .isEqualTo(RegistrationException.Reason.DELIVERY_UNAVAILABLE);
    }
}
