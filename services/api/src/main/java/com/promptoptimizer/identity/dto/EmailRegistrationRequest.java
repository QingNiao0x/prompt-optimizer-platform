package com.promptoptimizer.identity.dto;

import com.promptoptimizer.identity.domain.UserDisplayNames;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 使用邮箱验证码创建个人账户。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record EmailRegistrationRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式无效")
        @Size(max = 320, message = "邮箱长度不能超过 320 个字符")
        String email,

        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "\\d{6}", message = "验证码必须是 6 位数字")
        String verificationCode,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 200, message = "密码长度必须在 8 到 200 个字符之间")
        String password,

        @Size(max = UserDisplayNames.MAX_LENGTH, message = "昵称不能超过80个字符")
        @Pattern(regexp = UserDisplayNames.SINGLE_LINE_PATTERN, message = "昵称不能包含换行或控制字符")
        String displayName
) {
    /** 昵称为可选资料；空白和旧客户端未传字段保持原有默认命名行为。 */
    public EmailRegistrationRequest {
        displayName = UserDisplayNames.optional(displayName);
    }

    /** 保留已有服务调用和测试夹具的无昵称构造方式。 */
    public EmailRegistrationRequest(String email, String verificationCode, String password) {
        this(email, verificationCode, password, null);
    }

    @Override
    public String toString() { return "EmailRegistrationRequest[redacted]"; }
}
