package com.promptoptimizer.identity.settings;

import com.promptoptimizer.identity.domain.UserIdentityKey;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.infrastructure.registration.RegistrationProperties;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.identity.service.VerificationEmailSender;
import com.promptoptimizer.identity.service.impl.LoginFailureGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 普通账户设置编排；复用SMTP配置与密码规则，云投递严格位于账户数据库事务之外。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@Profile("!local-mock")
public class AccountSettingsService {
    private static final String BROWSER_NONCE = AccountSettingsService.class.getName() + ".browser";
    private static final Duration TTL = Duration.ofMinutes(5);
    private final SecureRandom random = new SecureRandom();
    private final AccountSettingsMapper mapper;
    private final AccountSettingsTransactions transactions;
    private final CurrentActor currentActor;
    private final RegistrationProperties properties;
    private final VerificationEmailSender sender;
    private final LoginFailureGuard loginGuard;

    public AccountSettingsService(AccountSettingsMapper mapper, AccountSettingsTransactions transactions,
            CurrentActor currentActor, RegistrationProperties properties, VerificationEmailSender sender,
            LoginFailureGuard loginGuard) {
        this.mapper = mapper;
        this.transactions = transactions;
        this.currentActor = currentActor;
        this.properties = properties;
        this.sender = sender;
        this.loginGuard = loginGuard;
    }

    /** 读取数据库中的当前资料，昵称和语言变更后不依赖旧Session快照。 */
    public AccountSettingsView read() { return view(transactions.read(principal())); }

    /** 更新昵称，不改变认证版本或其他设备的登录状态。 */
    public AccountSettingsView profile(String displayName) { return view(transactions.profile(principal(), displayName)); }

    /** 更新账户语言，不接受任意地区代码写入数据库。 */
    public AccountSettingsView preferences(String locale) { return view(transactions.locale(principal(), locale)); }

    /** 原子预留发送预算后投递，失败保留预算，成功回写不再延长验证码时效。 */
    public ChallengeView challenge(String email, String password, HttpServletRequest request) {
        requireBinding();
        String target = email(email);
        var principal = principal();
        UUID id = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String userId = principal.actorIdentity().userId().toString();
        var challenge = new EmailBindingChallenge(id, principal.actorIdentity().userId(), fingerprint("email", target),
                browser(request), fingerprint("code", id + ":" + userId + ":" + target + ":" + code),
                "PENDING", 0, OffsetDateTime.now().plus(TTL));
        guarded(principal, request, () -> {
            transactions.prepare(principal, password, challenge, fingerprint("actor-budget", userId),
                    fingerprint("email-budget", target), fingerprint("ip-budget", request.getRemoteAddr()),
                    properties.getEmailHourlyLimit(), properties.getIpHourlyLimit());
            return null;
        });
        try {
            sender.sendBinding(target, code, TTL);
        } catch (RuntimeException exception) {
            mapper.failed(id);
            // 邮件原始异常可能持有收件人或验证码，不附带cause、不记录供应商正文。
            throw new AccountSettingsException(503, "EMAIL_DELIVERY_UNAVAILABLE", "验证码邮件暂时无法发送，请稍后重试。");
        }
        if (mapper.sent(id) != 1) throw new AccountSettingsException(409, "EMAIL_CHALLENGE_REPLACED", "验证码请求已失效，请重新获取。");
        return new ChallengeView(id, 300, 60);
    }

    /** 验证码绑定当前账户、浏览器和邮箱；成功后前端须重新登录。 */
    public AccountSettingsView email(String email, UUID id, String code, String password, HttpServletRequest request) {
        requireBinding();
        String target = email(email);
        var principal = principal();
        if (id == null || code == null || !code.matches("[0-9]{6}")) {
            throw new AccountSettingsException(400, "EMAIL_CODE_INVALID", "请输入六位数字验证码。");
        }
        String digest = fingerprint("code", id + ":" + principal.actorIdentity().userId() + ":" + target + ":" + code);
        return view(guarded(principal, request, () -> transactions.email(principal, password, id, target,
                fingerprint("email", target), browser(request), digest)));
    }

    /** 规范化用户名后执行密码重验和替换，不自动恢复旧身份。 */
    public AccountSettingsView username(String value, String password, HttpServletRequest request) {
        String normalized;
        try { normalized = UserIdentityKey.username(value).normalizedIdentifier(); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new AccountSettingsException(400, "USERNAME_INVALID", "用户名需为3至32位字母、数字、点、下划线或连字符，且以字母或数字开头。");
        }
        var principal = principal();
        return view(guarded(principal, request, () -> transactions.username(principal, password, normalized)));
    }

    /** 撤销全部设备的认证版本；实际Cookie清理由控制器在数据库提交后执行。 */
    public void logoutAll(String password, HttpServletRequest request) {
        var principal = principal();
        guarded(principal, request, () -> { transactions.logoutAll(principal, password); return null; });
    }

    /** 使用现有密码失败限流处理修改密码，不记录或返回密码。 */
    public void changePassword(String currentPassword, String newPassword, HttpServletRequest request) {
        var principal = principal();
        guarded(principal, request, () -> { transactions.changePassword(principal, currentPassword, newPassword); return null; });
    }

    /** 二次确认后逻辑注销，不删除历史、成员关系或租户。 */
    public void deactivate(String password, String confirmation, HttpServletRequest request) {
        var principal = principal();
        guarded(principal, request, () -> { transactions.deactivate(principal, password, confirmation); return null; });
    }

    /** 仅投影当前账号的有效身份，联系邮箱字段不作为身份占用依据。 */
    private AccountSettingsView view(UserAccountEntity account) {
        var email = mapper.activeIdentity(account.getId(), "EMAIL");
        var username = mapper.activeIdentity(account.getId(), "USERNAME");
        boolean deletable = !"PLATFORM_ADMIN".equals(account.getPlatformRole()) || mapper.activeAdminCount() > 1;
        return new AccountSettingsView(account.getDisplayName(), email == null ? null : email.getNormalizedIdentifier(),
                username == null ? null : username.getNormalizedIdentifier(), account.getLocale(), bindingAvailable(), deletable);
    }

    /** 真实服务端Principal和CurrentActor必须一致，客户端不能选择目标账户。 */
    private AuthenticatedUser principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)
                || !currentActor.require().userId().equals(principal.actorIdentity().userId())) {
            throw new AuthenticationCredentialsNotFoundException("请先登录。");
        }
        return principal;
    }

    /** 本地日志模式、缺失密钥或SMTP配置时关闭邮箱所有权验证，保留其他设置。 */
    private boolean bindingAvailable() {
        return properties.isEnabled() && "smtp".equals(properties.getDeliveryMode())
                && properties.getVerificationSecret() != null && properties.getVerificationSecret().length() >= 32
                && sender.supportsBinding();
    }

    private void requireBinding() {
        if (!bindingAvailable()) throw new AccountSettingsException(503, "EMAIL_BINDING_UNAVAILABLE", "邮箱绑定服务暂未开启，请稍后再试。");
    }

    /** 重验密码沿用既有失败锁定；绑定成功不清空登录失败预算。 */
    private <T> T guarded(AuthenticatedUser principal, HttpServletRequest request, Supplier<T> action) {
        String identifier = "settings:" + principal.actorIdentity().userId();
        loginGuard.checkAllowed(identifier, request.getRemoteAddr());
        try { return action.get(); }
        catch (AccountSettingsException exception) {
            if ("CURRENT_PASSWORD_INVALID".equals(exception.code())) loginGuard.recordFailure(identifier, request.getRemoteAddr());
            throw exception;
        }
    }

    /** 邮箱语法需在HTTP校验外再次保护，规范化严格复用现有身份键。 */
    private static String email(String value) {
        if (value == null || value.length() > 254 || !value.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new AccountSettingsException(400, "EMAIL_INVALID", "请输入有效的邮箱地址。");
        }
        return UserIdentityKey.email(value).normalizedIdentifier();
    }

    /** 随机浏览器绑定值只存在服务端Session，不信任请求自报的浏览器指纹。 */
    private String browser(HttpServletRequest request) {
        var session = request.getSession();
        String nonce;
        synchronized (session) {
            Object value = session.getAttribute(BROWSER_NONCE);
            nonce = value instanceof String existing ? existing : UUID.randomUUID().toString();
            session.setAttribute(BROWSER_NONCE, nonce);
        }
        return fingerprint("browser", nonce);
    }

    /** 使用独立业务密钥且隔离用途，禁止记录原始值和摘要密钥。 */
    private String fingerprint(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getVerificationSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("email-binding:" + purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("邮箱验证摘要不可用。", exception); }
    }

    /** 不含验证秘密的挑战收据，供前端倒计时和后续提交使用。 */
    public record ChallengeView(UUID challengeId, long expiresInSeconds, long resendAfterSeconds) { }
}
