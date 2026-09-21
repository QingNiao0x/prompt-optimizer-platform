package com.promptoptimizer.identity.infrastructure.security;

import com.promptoptimizer.identity.domain.UserIdentityKey;
import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.infrastructure.persistence.UserAccountEntity;
import com.promptoptimizer.identity.infrastructure.persistence.UserAccountRepository;
import com.promptoptimizer.identity.infrastructure.persistence.UserIdentityRepository;
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

    private final UserAccountRepository repository;
    private final UserIdentityRepository identityRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public BootstrapUserPasswordInitializer(
            UserAccountRepository repository,
            UserIdentityRepository identityRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.security.bootstrap-user.email:demo@local}") String email,
            @Value("${app.security.bootstrap-user.password:}") String password
    ) {
        this.repository = repository;
        this.identityRepository = identityRepository;
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
        UserAccountEntity account = identityRepository
                .findByIdentityTypeAndIssuerAndNormalizedIdentifierAndStatus(
                        key.type(),
                        key.issuer(),
                        key.normalizedIdentifier(),
                        UserIdentityStatus.ACTIVE
                )
                .flatMap(identity -> repository.findById(identity.getUserId()))
                .orElse(null);
        if (account == null) {
            LOGGER.warn("未找到初始化登录账户，请先通过受控迁移或后续注册流程创建账户");
            return;
        }
        if (account.getPasswordHash() != null && !account.getPasswordHash().isBlank()) {
            return;
        }
        if (password == null || password.isBlank()) {
            LOGGER.warn("登录账户尚无密码；请设置 BOOTSTRAP_USER_PASSWORD 后重启服务完成一次性初始化");
            return;
        }
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("BOOTSTRAP_USER_PASSWORD 至少 12 个字符且 UTF-8 编码不能超过 72 字节");
        }
        account.setPasswordHash(passwordEncoder.encode(password));
        repository.save(account);
        LOGGER.info("已为初始用户安全写入 BCrypt 密码哈希；后续启动不会覆盖现有密码");
    }
}
