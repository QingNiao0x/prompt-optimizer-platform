package com.promptoptimizer.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 请求发送邮箱注册验证码。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record EmailRegistrationCodeRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式无效")
        @Size(max = 320, message = "邮箱长度不能超过 320 个字符")
        String email
) {
}
