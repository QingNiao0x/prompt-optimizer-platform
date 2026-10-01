package com.promptoptimizer.provider.dto;

/**
 * 面向用户的模型摘要；displayName 是管理员维护的具体模型版本，id 仅用于调用路由。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AvailableModel(String id, String displayName, String provider, boolean defaultModel) {
}
