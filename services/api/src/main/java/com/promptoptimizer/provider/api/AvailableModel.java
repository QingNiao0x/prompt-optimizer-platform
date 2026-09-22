package com.promptoptimizer.provider.api;

/**
 * 可供当前工作区选择的模型摘要，不包含端点、API Key 或其他运行时密钥。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AvailableModel(
        String id,
        String displayName,
        String provider,
        boolean defaultModel
) {
}
