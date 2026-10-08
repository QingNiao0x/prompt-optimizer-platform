package com.promptoptimizer.identity.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 使用邮箱或用户名进行密码登录的请求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record LoginRequest(
        @JsonAlias("email")
        @NotBlank(message = "邮箱或用户名不能为空")
        @Size(max = 320, message = "登录标识长度不能超过 320 个字符")
        String identifier,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 200, message = "密码长度必须在 8 到 200 个字符之间")
        String password,

        @NotBlank(message = "图形验证码不能为空")
        @Size(max = 8, message = "图形验证码长度不正确")
        String captcha,
        com.promptoptimizer.identity.domain.UserIdentityType identityType
) {
    /** 旧邮箱/用户名客户端及测试保持兼容，数字用户名不会被自动识别为手机号。 */
    public LoginRequest(String identifier, String password, String captcha) { this(identifier, password, captcha, null); }

    /** 请求对象不可把密码、图形验证码或登录标识写入诊断。 */
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
