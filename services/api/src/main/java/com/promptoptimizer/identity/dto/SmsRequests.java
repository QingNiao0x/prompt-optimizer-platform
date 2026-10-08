package com.promptoptimizer.identity.dto;

import com.promptoptimizer.identity.domain.SmsPurpose;
import jakarta.validation.constraints.*;
import java.util.UUID;

/**
 * 短信HTTP输入；身份、用途和密码只用于本次服务端校验，禁止默认record诊断输出。
 * @author QingNiao
 * @since 0.1.0
 */
public final class SmsRequests {
    private SmsRequests() { }

    /** 匿名发码仅允许REGISTER/LOGIN；BIND由已登录独立接口固定。 */
    public record Challenge(@NotBlank @Size(max=32) String phone, @NotNull SmsPurpose purpose,
            @NotBlank @Size(max=8) String captcha) {
        @Override public String toString() { return "SmsChallengeRequest[redacted]"; }
    }
    /** 手机注册沿用已有密码规则；是否符合复杂度由统一PasswordPolicy判断。 */
    public record Registration(@NotBlank @Size(max=32) String phone, @NotNull UUID challengeId,
            @NotBlank @Pattern(regexp="[0-9]{6}") String verificationCode, @NotBlank @Size(min=8,max=200) String password) {
        @Override public String toString() { return "PhoneRegistrationRequest[redacted]"; }
    }
    /** 短信登录不允许提交目标用户ID、角色或工作区。 */
    public record Login(@NotBlank @Size(max=32) String phone, @NotNull UUID challengeId,
            @NotBlank @Pattern(regexp="[0-9]{6}") String verificationCode) {
        @Override public String toString() { return "SmsLoginRequest[redacted]"; }
    }
    /** 发码前验证已有账户密码和图形验证码，防止借他人会话轰炸短信。 */
    public record BindingChallenge(@NotBlank @Size(max=32) String phone,
            @NotBlank @Size(max=200) String currentPassword, @NotBlank @Size(max=8) String captcha) {
        @Override public String toString() { return "BindingChallengeRequest[redacted]"; }
    }
    /** 最终提交仍复核当前密码和真实账户状态，不信任发码时的快照。 */
    public record Binding(@NotBlank @Size(max=32) String phone, @NotNull UUID challengeId,
            @NotBlank @Pattern(regexp="[0-9]{6}") String verificationCode, @NotBlank @Size(max=200) String currentPassword) {
        @Override public String toString() { return "PhoneBindingRequest[redacted]"; }
    }
    /** 挑战编号只是关联标识，不包含验证码、签名或云凭据。 */
    public record ChallengeView(UUID challengeId, long expiresInSeconds, long resendAfterSeconds) { }
    /** 公开功能开关，不说明云账号、签名、密钥或方案名。 */
    public record Capabilities(boolean phoneRegistration, boolean smsLogin, boolean phoneBinding) { }
}
