package com.promptoptimizer.settings.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Provider 配置的对外摘要，永不包含明文 API Key。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ProviderConfigSummary(
        UUID id,
        String providerType,
        String displayName,
        String endpointUrl,
        String modelName,
        String apiKeyLast4,
        Map<String, Object> parameters,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
