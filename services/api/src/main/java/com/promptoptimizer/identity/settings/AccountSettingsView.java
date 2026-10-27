package com.promptoptimizer.identity.settings;

/**
 * 当前账户的可编辑资料投影；不含密码哈希、认证版本或供应商配置。
 * @author QingNiao
 * @since 0.1.0
 */
public record AccountSettingsView(String displayName, String email, String username, String locale,
        boolean emailBindingAvailable, boolean accountDeletionAvailable) { }
