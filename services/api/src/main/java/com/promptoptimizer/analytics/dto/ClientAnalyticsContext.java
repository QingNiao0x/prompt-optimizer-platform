package com.promptoptimizer.analytics.dto;

import java.util.UUID;

/**
 * 当前认证账号和独立审计登录关联号，不包含认证 Session ID、Token、IP 或地理位置。
 * 浏览器只用它确认待投递事件是否仍属于同一身份与登录上下文。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ClientAnalyticsContext(UUID userId, UUID loginSessionId) { }
