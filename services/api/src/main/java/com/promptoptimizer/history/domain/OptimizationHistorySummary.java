package com.promptoptimizer.history.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 历史列表预览；modelName 为原调用标识，modelVersion 为调用时的平台版本快照，旧记录可为空。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record OptimizationHistorySummary(
        UUID id,
        String templateCode,
        String rawPromptPreview,
        String providerName,
        String modelName,
        boolean mock,
        Integer latencyMs,
        OffsetDateTime createdAt,
        String modelVersion
) {
}
