package com.promptoptimizer.enhancement.domain;

/**
 * 本次增强所使用的模型提供方元数据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ProviderMetadata(
        String provider,
        String model,
        boolean mock
) {
}
