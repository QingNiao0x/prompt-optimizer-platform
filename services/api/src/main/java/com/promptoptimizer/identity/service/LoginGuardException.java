package com.promptoptimizer.identity.service;

import org.springframework.security.core.AuthenticationException;

/**
 * 登录前的验证码或锁定拒绝。消息可以返回给用户，不包含验证码、密码或登录标识。
 */
public class LoginGuardException extends AuthenticationException {

    private final String code;
    private final long retryAfterSeconds;

    public LoginGuardException(String code, String message, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
