package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.domain.MainlandPhone;
import com.promptoptimizer.identity.service.SmsException;
import com.promptoptimizer.identity.service.impl.PhoneVerificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.stereotype.Component;

/**
 * 独立短信认证通道；先消费一次性凭据，再复核数据库身份，绝不使用固定密码模拟登录。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@Profile("!local-mock")
public class SmsAuthenticationProvider implements AuthenticationProvider {
    private final PhoneVerificationService verification;
    private final DatabaseUserDetailsService users;

    public SmsAuthenticationProvider(PhoneVerificationService verification, DatabaseUserDetailsService users) {
        this.verification = verification;
        this.users = users;
    }

    /** 仅有效普通账户可以使用短信单因素建立会话；管理员仍需密码。 */
    @Override public Authentication authenticate(Authentication authentication) {
        SmsAuthenticationToken token = (SmsAuthenticationToken) authentication;
        var credentials = token.getCredentials();
        verification.login(credentials, token.request());
        AuthenticatedUser user = (AuthenticatedUser) users.loadUserByUsername("PHONE:" + MainlandPhone.normalize(credentials.phone()));
        new AccountStatusUserDetailsChecker().check(user);
        if (user.getAuthorities().stream().anyMatch(value -> "ROLE_PLATFORM_ADMIN".equals(value.getAuthority()))) {
            throw new SmsException(403, "SMS_PASSWORD_LOGIN_REQUIRED", "该账户请使用密码登录。", 0);
        }
        token.eraseCredentials();
        return new SmsAuthenticationToken(user);
    }

    @Override public boolean supports(Class<?> authentication) {
        return SmsAuthenticationToken.class.equals(authentication);
    }
}
