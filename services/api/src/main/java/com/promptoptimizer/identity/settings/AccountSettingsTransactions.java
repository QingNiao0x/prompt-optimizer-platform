package com.promptoptimizer.identity.settings;

import com.promptoptimizer.identity.domain.UserDisplayNames;
import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 设置写入的数据库事务边界；身份消费与写入原子提交，任何云调用均由外层编排执行。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@Profile("!local-mock")
public class AccountSettingsTransactions {
    private final AccountSettingsMapper mapper;
    private final PasswordEncoder passwords;

    public AccountSettingsTransactions(AccountSettingsMapper mapper, PasswordEncoder passwords) {
        this.mapper = mapper;
        this.passwords = passwords;
    }

    /** 只向调用方返回真实活动账户，不能仅信任会话快照。 */
    @Transactional(readOnly = true)
    public UserAccountEntity read(AuthenticatedUser principal) { return requireAccount(principal, false); }

    /** 昵称沿用既有长度与控制字符规则；空白不清除已有有效显示名。 */
    @Transactional
    public UserAccountEntity profile(AuthenticatedUser principal, String name) {
        var account = requireAccount(principal, true);
        String normalized = UserDisplayNames.resolve(name, null);
        if (normalized == null) throw invalid("DISPLAY_NAME_REQUIRED", "请输入显示名称。");
        mapper.profile(account.getId(), normalized);
        account.setDisplayName(normalized);
        return account;
    }

    /** 只接受明确开放的两种语言，持久化到当前账户。 */
    @Transactional
    public UserAccountEntity locale(AuthenticatedUser principal, String locale) {
        var account = requireAccount(principal, true);
        if (!"zh-CN".equals(locale) && !"en".equals(locale)) throw invalid("LOCALE_INVALID", "请选择中文或英文。");
        mapper.locale(account.getId(), locale);
        account.setLocale(locale);
        return account;
    }

    /** 发码前重验密码、预留所有预算并替换旧挑战；SMTP失败时这些预算仍然保留。 */
    @Transactional
    public void prepare(AuthenticatedUser principal, String password, EmailBindingChallenge challenge,
            String actorBudget, String emailBudget, String ipBudget, int emailLimit, int ipLimit) {
        reauthenticate(principal, password);
        // 固定账户锁顺序，再依次获取预算行锁，保持并发发码与账户修改串行。
        reserve(actorBudget, 5, 60);
        reserve(emailBudget, emailLimit, 60);
        reserve(ipBudget, ipLimit, 0);
        mapper.supersede(principal.actorIdentity().userId());
        mapper.insertChallenge(challenge);
    }

    /** 用户名变更保留旧键占用；只有新身份成功插入才递增安全版本。 */
    @Transactional
    public UserAccountEntity username(AuthenticatedUser principal, String password, String username) {
        var account = reauthenticate(principal, password);
        replaceIdentity(account, "USERNAME", username);
        return account;
    }

    /**
     * 挑战锁串行化并发核验；仅验证码错误允许提交尝试数，正确码消费与身份写入整体回滚或提交。
     */
    @Transactional(noRollbackFor = AccountSettingsException.CodeRejected.class)
    public UserAccountEntity email(AuthenticatedUser principal, String password, UUID challengeId,
            String target, String emailFingerprint, String browserFingerprint, String digest) {
        var account = reauthenticate(principal, password);
        var challenge = mapper.challenge(challengeId);
        if (challenge == null || !account.getId().equals(challenge.userId())
                || !constantEquals(challenge.emailFingerprint(), emailFingerprint)
                || !constantEquals(challenge.browserFingerprint(), browserFingerprint)) {
            throw invalid("EMAIL_CODE_INVALID", "验证码无效，请重新获取。");
        }
        if (!"SENT".equals(challenge.status()) || !challenge.expiresAt().isAfter(OffsetDateTime.now())) {
            throw invalid("EMAIL_CODE_EXPIRED", "验证码已失效，请重新获取。");
        }
        if (!constantEquals(challenge.codeDigest(), digest)) {
            mapper.badAttempt(challenge.id());
            throw new AccountSettingsException.CodeRejected(challenge.attempts() + 1 >= 5
                    ? "EMAIL_CODE_ATTEMPTS_EXHAUSTED" : "EMAIL_CODE_INVALID",
                    challenge.attempts() + 1 >= 5 ? "验证码尝试次数已用尽，请重新获取。" : "验证码错误，请重试。");
        }
        // 先检查占用但不转移；冲突不暴露对方资料，也不消费成功授权。
        replaceIdentity(account, "EMAIL", target);
        if (mapper.consume(challenge.id()) != 1) throw invalid("EMAIL_CODE_EXPIRED", "验证码已失效，请重新获取。");
        return account;
    }

    /** 全设备登出只递增权威版本，Redis清理失败不能恢复旧会话。 */
    @Transactional
    public void logoutAll(AuthenticatedUser principal, String password) {
        var account = reauthenticate(principal, password);
        mapper.securityChange(account.getId(), null, false);
    }

    /** 注销保留所有业务数据与身份占用，并串行保护最后一位活动平台管理员。 */
    @Transactional
    public void deactivate(AuthenticatedUser principal, String password, String confirmation) {
        if (!"DELETE".equals(confirmation)) throw invalid("ACCOUNT_DELETION_CONFIRMATION_REQUIRED", "请确认注销账号后再继续。");
        // 所有注销使用同一事务锁，不能在账户行锁之后竞争管理员计数锁。
        mapper.lockAdminDeletion();
        var account = reauthenticate(principal, password);
        if ("PLATFORM_ADMIN".equals(account.getPlatformRole()) && mapper.activeAdminCount() <= 1) {
            throw new AccountSettingsException(409, "LAST_PLATFORM_ADMIN", "最后一位活动平台管理员不能注销账号，请先完成管理员交接。");
        }
        mapper.revokeAll(account.getId());
        mapper.deactivate(account.getId());
        mapper.supersede(account.getId());
    }

    /** 账户行锁同时约束密码校验、身份归属和登录快照版本。 */
    private UserAccountEntity reauthenticate(AuthenticatedUser principal, String password) {
        var account = requireAccount(principal, true);
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
                || account.getPasswordHash() == null || !passwords.matches(password, account.getPasswordHash())) {
            throw new AccountSettingsException(403, "CURRENT_PASSWORD_INVALID", "当前密码不正确，请重试。");
        }
        return account;
    }

    /** 活动账户、真实身份和认证版本同时成立，才允许当前会话继续访问。 */
    private UserAccountEntity requireAccount(AuthenticatedUser principal, boolean lock) {
        var actor = principal.actorIdentity();
        var account = mapper.account(actor.userId(), lock);
        if (account == null || account.getDeletedAt() != null || !"ACTIVE".equals(account.getStatus())
                || !actor.tenantId().equals(account.getTenantId()) || account.getSecurityVersion() != principal.getSecurityVersion()) {
            throw new AccountSettingsException(401, "SESSION_REVOKED", "登录状态已失效，请重新登录。");
        }
        var identity = principal.getIdentityId() == null ? null : mapper.identity(principal.getIdentityId());
        if (identity == null || !account.getId().equals(identity.getUserId()) || identity.getStatus() != UserIdentityStatus.ACTIVE) {
            throw new AccountSettingsException(401, "SESSION_REVOKED", "登录身份已失效，请重新登录。");
        }
        return account;
    }

    /** 不恢复REVOKED键；并发唯一冲突返回0时回滚旧身份撤销，不能部分修改。 */
    private void replaceIdentity(UserAccountEntity account, String type, String identifier) {
        UserIdentityEntity existing = mapper.identityKey(type, identifier);
        if (existing != null && account.getId().equals(existing.getUserId()) && existing.getStatus() == UserIdentityStatus.ACTIVE) {
            // 重复提交保留原身份；该安全操作仍撤销其他设备，保证前端登出语义一致。
            mapper.securityChange(account.getId(), identifier, "EMAIL".equals(type));
            account.setSecurityVersion(account.getSecurityVersion() + 1);
            if ("EMAIL".equals(type)) account.setEmail(identifier);
            return;
        }
        if (existing != null) throw occupied(type);
        mapper.revokeType(account.getId(), type);
        if (mapper.insertIdentity(UUID.randomUUID(), account.getId(), type, identifier) != 1) throw occupied(type);
        mapper.securityChange(account.getId(), identifier, "EMAIL".equals(type));
        account.setSecurityVersion(account.getSecurityVersion() + 1);
        if ("EMAIL".equals(type)) account.setEmail(identifier);
    }

    /** 预算按数据库时间更新，冷却和小时限制共用原子冲突条件。 */
    private void reserve(String key, int limit, int cooldown) {
        if (mapper.reserveBudget(key, limit, cooldown) != 1) {
            throw new AccountSettingsException(429, "EMAIL_BINDING_RATE_LIMITED", "请求过于频繁，请稍后再试。");
        }
    }

    private static AccountSettingsException occupied(String type) {
        return new AccountSettingsException(409, "EMAIL".equals(type) ? "EMAIL_ALREADY_BOUND" : "USERNAME_ALREADY_TAKEN",
                "EMAIL".equals(type) ? "该邮箱不可绑定，请使用其他邮箱或登录原账号。" : "该用户名不可用，请使用其他用户名。");
    }

    private static AccountSettingsException invalid(String code, String message) { return new AccountSettingsException(400, code, message); }

    private static boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
