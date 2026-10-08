package com.promptoptimizer.identity.infrastructure.registration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用虚拟验证码检查纯文本模板和预留文案，不连接任何邮件服务。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class VerificationEmailTemplateTest {

    @ParameterizedTest
    @EnumSource(VerificationEmailTemplate.class)
    void shouldReplaceVariablesWhenRenderingAnyTemplate(VerificationEmailTemplate template) {
        var content = template.render("012345", Duration.ofMinutes(5));

        assertThat(content.body()).contains("012345", "验证码在 5 分钟内有效", "请勿直接回复")
                .doesNotContain("{Code}", "{Minutes}");
        assertThat(content.toString()).doesNotContain("012345", content.body());
    }

    @Test
    void shouldUseApprovedRegistrationCopyWhenRenderingRegistration() {
        var content = VerificationEmailTemplate.REGISTRATION.render("012345", Duration.ofMinutes(5));

        assertThat(content.subject()).isEqualTo("【PromptOptimizer】邮箱注册验证码");
        assertThat(content.body()).contains("注册 PromptOptimizer-提示词优化工具 账号", "仅用于本次注册",
                "不要通过回复邮件提交验证码", "如果这不是您本人发起的操作，请忽略此邮件。");
    }

    @Test
    void shouldDistinguishBindingFromLoginWhenRenderingReservedTemplates() {
        assertThat(VerificationEmailTemplate.LOGIN.render("012345", Duration.ofMinutes(5)).subject())
                .isEqualTo("【PromptOptimizer】｜登录验证码");
        var binding = VerificationEmailTemplate.EMAIL_BINDING.render("012345", Duration.ofMinutes(5));
        assertThat(binding.subject()).isEqualTo("【PromptOptimizer】｜邮箱绑定验证");
        assertThat(binding.body()).contains("仅用于本次邮箱绑定验证", "不要将验证码提供给他人")
                .doesNotContain("仅用于本次登录验证");
    }

    @Test
    void shouldUseActualDurationWhenTtlIsNotFiveMinutes() {
        assertThat(VerificationEmailTemplate.REGISTRATION.render("012345", Duration.ofMinutes(10)).body())
                .contains("验证码在 10 分钟内有效");
        assertThat(VerificationEmailTemplate.REGISTRATION.render("012345", Duration.ofSeconds(90)).body())
                .contains("验证码在 1.5 分钟内有效");
        assertThat(VerificationEmailTemplate.REGISTRATION.render("012345", Duration.ofSeconds(30)).body())
                .contains("验证码在 0.5 分钟内有效");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "1234567", "１２３４５６", "12\r\n34", "<code>"})
    void shouldRejectInvalidCodeWhenRendering(String code) {
        assertThatThrownBy(() -> VerificationEmailTemplate.REGISTRATION.render(code, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("验证码邮件需要六位数字验证码和有效时长。");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldRejectNonPositiveDurationWhenRendering(long seconds) {
        assertThatThrownBy(() -> VerificationEmailTemplate.REGISTRATION.render("012345", Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMissingDurationWhenRendering() {
        assertThatThrownBy(() -> VerificationEmailTemplate.REGISTRATION.render("012345", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
