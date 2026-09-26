package com.promptoptimizer.identity.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 仅在显式提供现有用户 UUID 且平台尚无管理员时授予首位管理员身份。
 * 不创建内置账号或默认密码；后续管理员权限变更应走受控运维流程。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class PlatformAdminBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformAdminBootstrap.class);
    private final IdentityProvisioningMapper mapper;
    private final String bootstrapUserId;

    public PlatformAdminBootstrap(ObjectProvider<IdentityProvisioningMapper> mapperProvider,
            @Value("${app.security.bootstrap-admin-user-id:}") String bootstrapUserId) {
        this.mapper = mapperProvider.getIfAvailable();
        this.bootstrapUserId = bootstrapUserId == null ? "" : bootstrapUserId.trim();
    }

    /** 首次启用时只晋升已存在、已启用且已完成注册的用户。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        if (bootstrapUserId.isBlank()) return;
        if (mapper == null) throw new IllegalStateException("管理员初始化需要数据库连接");
        UUID userId = UUID.fromString(bootstrapUserId);
        Boolean consumed = mapper.selectAdminBootstrapConsumedForUpdate();
        if (Boolean.TRUE.equals(consumed)) return;
        if (mapper.countPlatformAdmins() > 0) {
            markConsumed();
            return;
        }
        int updated = mapper.promoteActiveUserToAdmin(userId);
        if (updated != 1) {
            throw new IllegalStateException("管理员初始化失败：指定的已启用用户不存在");
        }
        markConsumed();
        LOGGER.info("event=platform.admin.bootstrapped userId={}", userId);
    }

    private void markConsumed() {
        mapper.markAdminBootstrapConsumed();
    }
}
