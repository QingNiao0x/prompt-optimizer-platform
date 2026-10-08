package com.promptoptimizer.identity.service.impl;

import com.promptoptimizer.identity.domain.*;
import com.promptoptimizer.identity.entity.*;
import com.promptoptimizer.identity.mapper.*;
import com.promptoptimizer.identity.security.AuthenticatedUser;
import com.promptoptimizer.identity.service.SmsException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 手机注册、绑定及短信登录资格的事务边界；挑战消费和账户写入同库提交。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@Profile("!local-mock")
public class SmsAccountTransactions {
    private final SmsChallengeMapper challenges;
    private final SmsAccountMapper accounts;
    private final UserAccountMapper users;
    private final UserIdentityMapper identities;
    private final IdentityProvisioningMapper provisioning;
    private final PasswordEncoder passwords;

    public SmsAccountTransactions(SmsChallengeMapper challenges, SmsAccountMapper accounts, UserAccountMapper users,
            UserIdentityMapper identities, IdentityProvisioningMapper provisioning, PasswordEncoder passwords) {
        this.challenges = challenges; this.accounts = accounts; this.users = users; this.identities = identities;
        this.provisioning = provisioning; this.passwords = passwords;
    }

    /** 提交绑定前重新验证账户、当前登录身份和当前密码，不接受客户端userId。 */
    @Transactional
    public void checkBindingActor(AuthenticatedUser actor, String password) { checkedActor(actor, password); }

    /** 返回安全结果而非在事务内抛出预期冲突，确保冲突也消费一次性授权。 */
    @Transactional
    public Outcome complete(UUID id, SmsPurpose purpose, String phone, String fingerprint, String browser,
            AuthenticatedUser actor, String passwordHash, String currentPassword) {
        challenges.lockSubject(SmsChallengeTransactions.subject(fingerprint, purpose));
        UUID actorId = actor == null ? null : actor.actorIdentity().userId();
        var challenge = SmsChallengeTransactions.checked(challenges.find(id), purpose, fingerprint, browser, actorId);
        challenges.lockSubject("identity:" + fingerprint);
        if (purpose == SmsPurpose.BIND) checkedActor(actor, currentPassword);
        if (challenge.state() == SmsChallengeState.CONSUMED) {
            if (purpose == SmsPurpose.LOGIN) throw SmsException.invalid();
            return new Outcome(challenge.resultUserId(), challenge.resultCode());
        }
        if (challenge.state() != SmsChallengeState.VERIFIED) throw SmsException.invalid();

        // 跨用途仍共享身份锁；注册与绑定竞争同号码时，不创建孤立租户或接管原所有者。
        UserIdentityEntity identity = identities.selectByLoginKey(UserIdentityType.PHONE, "local", phone);
        Outcome result = switch (purpose) {
            case REGISTER -> register(phone, passwordHash, identity);
            case BIND -> bind(actorId, phone, identity);
            case LOGIN -> login(identity);
        };
        if (challenges.consume(id, result.userId(), result.code()) != 1) throw SmsException.invalid();
        return result;
    }

    /** 号码已占用时无账户写入；角色固定USER，联系邮箱为空而非伪造邮箱。 */
    private Outcome register(String phone, String passwordHash, UserIdentityEntity identity) {
        if (identity != null) return new Outcome(null, "PHONE_ALREADY_REGISTERED");
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), workspace = UUID.randomUUID();
        provisioning.insertTenant(tenant, "个人账户");
        provisioning.insertUserAccount(user, tenant, null, "新用户", passwordHash, "USER");
        provisioning.insertWorkspace(workspace, tenant, "个人工作区", "注册时自动创建的个人工作区", user);
        provisioning.insertWorkspaceMember(workspace, user, "OWNER");
        if (accounts.insertPhone(UUID.randomUUID(), user, phone) != 1) {
            // 非本服务写入路径的并发冲突必须回滚整套新账户，不能留下部分创建的资源。
            throw new SmsException(409, "PHONE_ALREADY_REGISTERED", "该手机号已注册，请直接登录。");
        }
        return new Outcome(user, "OK");
    }

    /** 先判原所有者，再判账户已有手机；撤销身份也不释放号码归属。 */
    private Outcome bind(UUID actor, String phone, UserIdentityEntity identity) {
        if (identity != null) {
            return identity.getUserId().equals(actor) && identity.getStatus() == UserIdentityStatus.ACTIVE
                    ? new Outcome(actor, "OK") : new Outcome(null, "PHONE_ALREADY_BOUND");
        }
        if (accounts.activePhone(actor) != null) return new Outcome(null, "PHONE_BINDING_EXISTS");
        return accounts.insertPhone(UUID.randomUUID(), actor, phone) == 1
                ? new Outcome(actor, "OK") : new Outcome(null, "PHONE_ALREADY_BOUND");
    }

    /** 云端通过也不能绕过禁用/锁定/撤销、租户工作区或管理员限制。 */
    private Outcome login(UserIdentityEntity identity) {
        if (identity == null || identity.getStatus() != UserIdentityStatus.ACTIVE) return new Outcome(null, "AUTHENTICATION_FAILED");
        UserAccountEntity account = accounts.lockAccount(identity.getUserId());
        UserIdentityEntity freshIdentity = accounts.lockIdentity(identity.getId());
        if (account == null || !"ACTIVE".equals(account.getStatus()) || freshIdentity == null
                || freshIdentity.getStatus() != UserIdentityStatus.ACTIVE
                || !freshIdentity.getUserId().equals(account.getId())
                || users.selectDefaultWorkspaceId(account.getId(), account.getTenantId()) == null) {
            return new Outcome(null, "AUTHENTICATION_FAILED");
        }
        if (!"USER".equals(account.getPlatformRole())) return new Outcome(null, "SMS_PASSWORD_LOGIN_REQUIRED");
        return new Outcome(account.getId(), "OK");
    }

    /** 固定账户再身份的锁顺序；旧会话没有身份ID时要求重新登录。 */
    private void checkedActor(AuthenticatedUser actor, String password) {
        if (actor == null || actor.getIdentityId() == null) throw new BadCredentialsException("请重新登录后绑定手机号。");
        var account = accounts.lockAccount(actor.actorIdentity().userId());
        var identity = accounts.lockIdentity(actor.getIdentityId());
        if (account == null || !"ACTIVE".equals(account.getStatus()) || identity == null
                || identity.getStatus() != UserIdentityStatus.ACTIVE || !identity.getUserId().equals(account.getId())
                || !account.getTenantId().equals(actor.actorIdentity().tenantId())
                || users.selectDefaultWorkspaceId(account.getId(), account.getTenantId()) == null
                || password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
                || account.getPasswordHash() == null || !passwords.matches(password, account.getPasswordHash())) {
            throw new BadCredentialsException("当前密码错误或账户不可用。");
        }
    }

    /** 持久化的最小结果，不携带手机号或认证凭据。 */
    public record Outcome(UUID userId, String code) {
        /** 提交后才将业务冲突映射到HTTP错误，不回滚已消费记录。 */
        public void requireSuccess() {
            switch (code) {
                case "OK" -> { return; }
                case "PHONE_ALREADY_REGISTERED" -> throw new SmsException(409, code, "该手机号已注册，请直接登录。");
                case "PHONE_ALREADY_BOUND" -> throw new SmsException(409, code, "该手机号已绑定其他账号，无法绑定到当前账号。请使用原账号登录，或更换手机号。");
                case "PHONE_BINDING_EXISTS" -> throw new SmsException(409, code, "当前账号已绑定手机号，暂不支持修改绑定。");
                case "SMS_PASSWORD_LOGIN_REQUIRED" -> throw new SmsException(403, code, "该账户请使用密码登录。");
                default -> throw new SmsException(401, "PHONE_AUTHENTICATION_FAILED", "手机号尚未注册、验证码无效或账户不可用，请核对后重试。");
            }
        }
    }
}
