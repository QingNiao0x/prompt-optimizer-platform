package com.promptoptimizer.settings.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 提供固定的本地演示租户、用户和工作区标识。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class DemoContextProvider {

    private final UUID tenantId;
    private final UUID userId;
    private final UUID workspaceId;

    /**
     * 从配置读取本地演示上下文标识。
     */
    public DemoContextProvider(
            @Value("${app.demo.tenant-id}") String tenantId,
            @Value("${app.demo.user-id}") String userId,
            @Value("${app.demo.workspace-id}") String workspaceId
    ) {
        this.tenantId = UUID.fromString(tenantId.trim());
        this.userId = UUID.fromString(userId.trim());
        this.workspaceId = UUID.fromString(workspaceId.trim());
    }

    /**
     * 返回当前请求使用的本地演示上下文。
     */
    public DemoContext current() {
        return new DemoContext(tenantId, userId, workspaceId);
    }
}
