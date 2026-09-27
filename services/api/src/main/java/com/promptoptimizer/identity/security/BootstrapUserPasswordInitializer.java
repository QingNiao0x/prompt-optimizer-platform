package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.domain.UserIdentityKey;
import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 只在数据库中的演示账户尚无密码时，用环境变量完成一次性初始化。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@Profile("!local-mock")
public class BootstrapUserPasswordInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapUserPasswordInitializer.class);

    private final UserAccountMapper accountMapper;
    private final UserIdentityMapper identityMapper;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public BootstrapUserPasswordInitializer(
            UserAccountMapper accountMapper,
            UserIdentityMapper identityMapper,
            PasswordEncoder passwordEncoder,
            @Value("${app.security.bootstrap-user.email:demo@local}") String email,
            @Value("${app.security.bootstrap-user.password:}") String password
    ) {
        this.accountMapper = accountMapper;
        this.identityMapper = identityMapper;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        UserIdentityKey key;
        try {
            key = UserIdentityKey.email(email);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("初始化登录邮箱配置无效，请检查 BOOTSTRAP_USER_EMAIL");
            return;
        }
        UserIdentityEntity identity = identityMapper.selectByLoginKey(
                key.type(), key.issuer(), key.normalizedIdentifier());
        UserAccountEntity account = identity == null || identity.getStatus() != UserIdentityStatus.ACTIVE
                ? null
                : accountMapper.selectById(identity.getUserId());
        if (account == null) {
            LOGGER.warn("未找到活动的初始化登录身份或账户，请先通过受控迁移或后续注册流程创建账户");
            return;
        }
        if (account.getPasswordHash() != null && !account.getPasswordHash().isBlank()) {
            return;
        }
        if (password == null || password.isBlank()) {
            LOGGER.warn("登录账户尚无密码；请设置 BOOTSTRAP_USER_PASSWORD 后重启服务完成一次性初始化");
            return;
        }
        if (!com.promptoptimizer.identity.service.impl.PasswordPolicy.meets(password)) {
            throw new IllegalStateException(com.promptoptimizer.identity.service.impl.PasswordPolicy.rejectionMessage());
        }
        int updated = accountMapper.updatePasswordHash(account.getId(), passwordEncoder.encode(password));
        if (updated != 1) {
            throw new IllegalStateException("初始账户密码哈希写入失败");
        }
        LOGGER.info("已为初始用户安全写入 BCrypt 密码哈希；后续启动不会覆盖现有密码");
    }
}
