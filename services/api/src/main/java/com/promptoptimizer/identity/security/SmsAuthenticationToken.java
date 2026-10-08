package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.dto.SmsRequests;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * 短信专用认证凭据；请求与验证码只在认证前短暂存在，不进入持久化 Session。
 * @author QingNiao
 * @since 0.1.0
 */
public final class SmsAuthenticationToken extends AbstractAuthenticationToken {
    @java.io.Serial
    private static final long serialVersionUID = 1L;
    private final Object principal;
    private transient SmsRequests.Login credentials;
    private transient HttpServletRequest request;

    /** 创建未经核验的短信请求，不能由客户端指定认证成功标记。 */
    public SmsAuthenticationToken(SmsRequests.Login credentials, HttpServletRequest request) {
        super(null);
        this.principal = "sms";
        this.credentials = credentials;
        this.request = request;
    }

    /** 仅供同包的认证 Provider 创建不含验证码的已认证结果。 */
    SmsAuthenticationToken(AuthenticatedUser user) {
        super(user.getAuthorities());
        this.principal = user;
        super.setAuthenticated(true);
    }

    @Override public Object getPrincipal() { return principal; }
    @Override public SmsRequests.Login getCredentials() { return credentials; }
    HttpServletRequest request() { return request; }

    @Override public void setAuthenticated(boolean authenticated) {
        if (authenticated) throw new IllegalArgumentException("必须经过短信认证 Provider");
        super.setAuthenticated(false);
    }

    @Override public void eraseCredentials() {
        super.eraseCredentials();
        credentials = null;
        request = null;
    }

    @Override public String toString() { return "SmsAuthenticationToken[REDACTED]"; }
}
