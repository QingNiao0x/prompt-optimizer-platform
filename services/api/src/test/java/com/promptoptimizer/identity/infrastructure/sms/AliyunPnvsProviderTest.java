package com.promptoptimizer.identity.infrastructure.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.*;
import com.aliyun.teautil.models.RuntimeOptions;
import com.promptoptimizer.identity.domain.MainlandPhone;
import com.promptoptimizer.identity.domain.SmsPurpose;
import com.promptoptimizer.identity.service.SmsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SDK 边界测试完全模拟云端，不读取凭据或发送短信。 */
@ExtendWith(OutputCaptureExtension.class)
class AliyunPnvsProviderTest {
    private final Client client = mock(Client.class);
    private final SmsProperties properties = properties();
    private final AliyunPnvsProvider provider = new AliyunPnvsProvider(client, properties);

    @Test void sendsCloudGeneratedSixDigitsWithoutReturningCodeOrRetry() throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(new SendSmsVerifyCodeResponse()
                .setBody(new SendSmsVerifyCodeResponseBody().setSuccess(true).setCode("OK")));
        provider.send("+8613800000000", properties.scheme(SmsPurpose.REGISTER), "100001", "test-id");
        var request = ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
        var options = ArgumentCaptor.forClass(RuntimeOptions.class);
        verify(client).sendSmsVerifyCodeWithOptions(request.capture(), options.capture());
        assertThat(request.getValue().getCodeLength()).isEqualTo(6L);
        assertThat(request.getValue().getValidTime()).isEqualTo(300L);
        assertThat(request.getValue().getInterval()).isEqualTo(60L);
        assertThat(request.getValue().getDuplicatePolicy()).isEqualTo(1L);
        assertThat(request.getValue().getReturnVerifyCode()).isFalse();
        assertThat(request.getValue().getAutoRetry()).isZero();
        assertThat(request.getValue().getTemplateParam()).isEqualTo("{\"code\":\"##code##\",\"min\":\"5\"}");
        assertThat(request.getValue().getSchemeName()).isEqualTo("test-register");
        assertThat(options.getValue().getAutoretry()).isFalse();
    }

    @Test void apiSuccessAloneIsNotVerificationSuccess() throws Exception {
        var body = new CheckSmsVerifyCodeResponseBody().setSuccess(true).setCode("OK");
        when(client.checkSmsVerifyCodeWithOptions(any(), any())).thenReturn(new CheckSmsVerifyCodeResponse().setBody(body));
        assertThat(provider.verify("+8613800000000", "test-login", "123456", "test-id")).isFalse();
        body.setModel(new CheckSmsVerifyCodeResponseBody.CheckSmsVerifyCodeResponseBodyModel().setVerifyResult("FAIL"));
        assertThat(provider.verify("+8613800000000", "test-login", "123456", "test-id")).isFalse();
        body.getModel().setVerifyResult("PASS");
        assertThat(provider.verify("+8613800000000", "test-login", "123456", "test-id")).isTrue();
    }

    @Test void neverPrintsRawSdkErrorsAndNeverRetries(CapturedOutput output) throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenThrow(new RuntimeException("private-number private-code private-secret"));
        assertThatThrownBy(() -> provider.send("+8613800000000", "test-bind", "100004", "test-id"))
                .isInstanceOf(SmsException.class).hasMessageNotContaining("private");
        verify(client, times(1)).sendSmsVerifyCodeWithOptions(any(), any());
        assertThat(output).doesNotContain("private-number", "private-code", "private-secret", "+8613800000000");
    }

    @Test void defaultsAreOffAndEnabledConfigurationRejectsUnsafeProfiles() {
        assertThat(new SmsProperties().isEnabled()).isFalse();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local-sql-debug");
        assertThatThrownBy(() -> SmsConfiguration.validate(properties, environment)).isInstanceOf(IllegalStateException.class);
        assertThat(properties.scheme(SmsPurpose.LOGIN)).isNotEqualTo(properties.scheme(SmsPurpose.BIND));
        properties.setVerificationSecret("");
        assertThatThrownBy(() -> SmsConfiguration.validate(properties, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }

    @Test void canonicalPhoneAndHmacDoNotExposeIdentifiers() {
        assertThat(MainlandPhone.normalize("138-0000-0000")).isEqualTo("+8613800000000");
        assertThat(MainlandPhone.normalize(" +86 13800000000 ")).isEqualTo("+8613800000000");
        assertThat(MainlandPhone.masked("+8613800000000")).isEqualTo("+86 138****0000");
        assertThatThrownBy(() -> MainlandPhone.normalize("+12025550123")).isInstanceOf(SmsException.class);
        assertThatThrownBy(() -> MainlandPhone.normalize("123456")).isInstanceOf(SmsException.class);
        var fingerprint = new SmsFingerprint(properties);
        assertThat(fingerprint.of("phone", "+8613800000000")).hasSize(64).doesNotContain("13800000000");
        assertThat(fingerprint.of("phone", "example")).isNotEqualTo(fingerprint.of("ip", "example"));
    }

    private static SmsProperties properties() {
        var value = new SmsProperties();
        value.setSignName("TEST_PLACEHOLDER"); value.setSchemePrefix("test");
        value.setVerificationSecret("test-only-independent-secret-at-least-32-bytes");
        return value;
    }
}
