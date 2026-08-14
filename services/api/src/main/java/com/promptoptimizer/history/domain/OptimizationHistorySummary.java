package com.promptoptimizer.history.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 历史记录列表项，原始提示词只返回预览，完整内容由详情接口提供。
 */
public record OptimizationHistorySummary(
        UUID id,
        String templateCode,
        String rawPromptPreview,
        String providerName,
        String modelName,
        boolean mock,
        Integer latencyMs,
        OffsetDateTime createdAt
) {
}
