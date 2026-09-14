package com.promptoptimizer.policy.application;

import java.util.List;

/**
 * 平台不可被请求参数关闭的权限边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class PlatformPermissionPolicy {

    public static final List<String> PROTECTED_PATHS = List.of(
            ".env", "**/*.pem", "**/*.key", "生产环境配置"
    );
    public static final List<String> CONFIRMATION_ACTIONS = List.of(
            "删除或覆盖文件", "数据库结构迁移", "升级核心依赖", "生产环境部署"
    );

    private PlatformPermissionPolicy() {
    }
}
