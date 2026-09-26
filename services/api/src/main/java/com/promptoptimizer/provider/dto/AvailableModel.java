package com.promptoptimizer.provider.dto;

/**
 * 面向用户的模型摘要；仅包含可公开的标识和展示名称。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AvailableModel(String id, String displayName, String provider, boolean defaultModel) {
}
