package com.promptoptimizer.identity.service.impl;

import com.promptoptimizer.identity.domain.*;
import com.promptoptimizer.identity.dto.SmsRequests;
import com.promptoptimizer.identity.entity.SmsChallenge;
import com.promptoptimizer.identity.infrastructure.sms.*;
import com.promptoptimizer.identity.mapper.SmsChallengeMapper;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import com.promptoptimizer.identity.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * 短信应用编排；云端网络调用不持有数据库事务，最终授权由数据库一次性消费。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@Profile("!local-mock")
public class PhoneVerificationService {
    private static final String BROWSER_NONCE = "SMS_BROWSER_NONCE";
    private final SmsProperties properties;
    private final SmsFingerprint fingerprint;
    private final RedisSmsRateLimiter rateLimiter;
    private final SmsVerificationProvider provider;
    private final SmsChallengeTransactions transactions;
    private final SmsChallengeMapper challenges;
    private final SmsAccountTransactions accounts;
    private final PasswordEncoder passwords;
    private final LoginCaptchaService captcha;
    private final LoginFailureGuard guard;

    public PhoneVerificationService(SmsProperties properties, SmsFingerprint fingerprint, RedisSmsRateLimiter rateLimiter,
            SmsVerificationProvider provider, SmsChallengeTransactions transactions, SmsChallengeMapper challenges,
            SmsAccountTransactions accounts, PasswordEncoder passwords, LoginCaptchaService captcha, LoginFailureGuard guard) {
        this.properties = properties; this.fingerprint = fingerprint; this.rateLimiter = rateLimiter;
        this.provider = provider; this.transactions = transactions; this.challenges = challenges;
        this.accounts = accounts; this.passwords = passwords; this.captcha = captcha; this.guard = guard;
    }

    /** 发码接口不暴露号码归属；调用限制跨注册/登录/绑定共享，真实发送失败也不返还预算。 */
    public SmsRequests.ChallengeView issue(String input, SmsPurpose purpose, String imageCode,
            AuthenticatedUser actor, String currentPassword, HttpServletRequest request) {
        available();
        String phone = MainlandPhone.normalize(input);
        captcha.verifyAndConsume(request, imageCode);
        if (purpose == SmsPurpose.BIND) checkActor(actor, currentPassword, request);
        String phoneHash = fingerprint.of("phone", phone);
        UUID actorId = actor == null ? null : actor.actorIdentity().userId();
        rateLimiter.reserve(phoneHash, fingerprint.of("ip", request.getRemoteAddr()),
                actorId == null ? null : fingerprint.of("actor", actorId.toString()));
        SmsChallenge challenge = transactions.create(purpose, phoneHash, browser(request, true), actorId, properties.scheme(purpose));
        try {
            provider.send(phone, challenge.schemeName(), purpose == SmsPurpose.BIND ? properties.getBindingTemplate() : properties.getAccountTemplate(), challenge.id().toString());
            if (challenges.markSent(challenge.id()) != 1) throw SmsException.invalid();
        } catch (RuntimeException exception) {
            challenges.fail(challenge.id());
            throw exception;
        }
        return new SmsRequests.ChallengeView(challenge.id(), 300, 60);
    }

    /** 注册成功结果允许原浏览器恢复，但自动登录仍必须校验实际注册密码。 */
    public void register(SmsRequests.Registration request, HttpServletRequest http) {
        if (!PasswordPolicy.meets(request.password())) throw new SmsException(400, "PASSWORD_INVALID", PasswordPolicy.rejectionMessage());
        complete(request.phone(), request.challengeId(), request.verificationCode(), SmsPurpose.REGISTER,
                null, request.password(), null, http);
    }

    /** 普通用户短信认证入口；已消费的登录挑战不能再次签发会话。 */
    public void login(SmsRequests.Login request, HttpServletRequest http) {
        String key = "PHONE:" + MainlandPhone.normalize(request.phone());
        guard.checkAllowed(key, http.getRemoteAddr());
        try {
            complete(request.phone(), request.challengeId(), request.verificationCode(), SmsPurpose.LOGIN, null, null, null, http);
        } catch (SmsException exception) {
            if (exception.getStatus() == 400) guard.recordFailure(key, http.getRemoteAddr());
            throw exception;
        }
        // 不清空密码失败计数，短信登录不能用来重置密码攻击预算。
    }

    /** 首次绑定始终落在当前账户，不迁移、合并或恢复已撤销身份。 */
    public void bind(SmsRequests.Binding request, AuthenticatedUser actor, HttpServletRequest http) {
        checkActor(actor, request.currentPassword(), http);
        complete(request.phone(), request.challengeId(), request.verificationCode(), SmsPurpose.BIND,
                actor, null, request.currentPassword(), http);
    }

    /** 分离云校验和业务事务；VERIFIED作为短时授权持久化，数据库提交失败时无需重放云核验。 */
    private void complete(String input, UUID id, String code, SmsPurpose purpose, AuthenticatedUser actor,
            String registrationPassword, String currentPassword, HttpServletRequest request) {
        available();
        String phone = MainlandPhone.normalize(input), phoneHash = fingerprint.of("phone", phone), browser = browser(request, false);
        UUID actorId = actor == null ? null : actor.actorIdentity().userId(), token = UUID.randomUUID();
        SmsChallenge challenge = transactions.reserve(id, purpose, phoneHash, browser, actorId, token);
        if (challenge.state() != SmsChallengeState.VERIFIED && challenge.state() != SmsChallengeState.CONSUMED) {
            boolean passed;
            try { passed = provider.verify(phone, challenge.schemeName(), code, id.toString()); }
            catch (RuntimeException exception) { challenges.fail(id); throw exception; }
            if (challenges.verified(id, token, passed) != 1) throw SmsException.invalid();
            if (!passed) throw new SmsException(400, "SMS_CODE_INVALID", "验证码错误或已失效。");
        }
        // 高成本 BCrypt 放在有效挑战和云端核验之后，避免匿名随机挑战请求消耗哈希CPU。
        String passwordHash = purpose == SmsPurpose.REGISTER && challenge.state() != SmsChallengeState.CONSUMED
                ? passwords.encode(registrationPassword) : null;
        accounts.complete(id, purpose, phone, phoneHash, browser, actor, passwordHash, currentPassword).requireSuccess();
    }

    /** 敏感操作密码失败继续累计现有登录失败限制，不用发码成功来清零。 */
    private void checkActor(AuthenticatedUser actor, String password, HttpServletRequest request) {
        if (actor == null) throw new BadCredentialsException("请重新登录。");
        guard.checkAllowed(actor.getUsername(), request.getRemoteAddr());
        try { accounts.checkBindingActor(actor, password); }
        catch (BadCredentialsException exception) {
            guard.recordFailure(actor.getUsername(), request.getRemoteAddr());
            throw new SmsException(403, "CURRENT_PASSWORD_INVALID", "当前密码错误或账户不可用，请检查密码或重新登录。");
        }
    }

    private void available() { if (!properties.isEnabled()) throw SmsException.unavailable(); }

    /** 独立随机值随Session ID轮换保留；只保存其HMAC到数据库，不允许跨浏览器使用挑战。 */
    private String browser(HttpServletRequest request, boolean issue) {
        var session = request.getSession(issue);
        if (session == null) throw SmsException.invalid();
        Object value = session.getAttribute(BROWSER_NONCE);
        if (value == null && issue) {
            value = UUID.randomUUID().toString();
            session.setAttribute(BROWSER_NONCE, value);
        }
        if (!(value instanceof String nonce)) throw SmsException.invalid();
        return fingerprint.of("browser", nonce);
    }
}
