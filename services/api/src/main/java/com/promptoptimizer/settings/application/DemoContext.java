package com.promptoptimizer.settings.application;

import java.util.UUID;

/**
 * MVP 阶段的本地演示上下文，接入真实登录体系后由安全上下文替换。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record DemoContext(
        UUID tenantId,
        UUID userId,
        UUID workspaceId
) {
}
