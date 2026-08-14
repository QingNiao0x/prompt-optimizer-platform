package com.promptoptimizer.history.domain;

import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.domain.PromptSection;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 单条历史记录详情，包含原始提示词、优化结果、脱敏上下文摘要和增强选项。
 */
public record OptimizationHistoryDetail(
        UUID id,
        String rawPrompt,
        String optimizedPrompt,
        List<PromptSection> sections,
        Map<String, Object> contextSummary,
        List<String> ambiguities,
        List<String> appliedConstraints,
        String templateCode,
        String providerName,
        String modelName,
        boolean mock,
        Integer latencyMs,
        OffsetDateTime createdAt,
        boolean includePermissionBoundaries,
        boolean includeExamples,
        List<ConversationMessage> conversationHistory,
        Map<String, Object> permissionPolicy
) {

    /**
     * 对列表字段做防御性拷贝。
     */
    public OptimizationHistoryDetail {
        sections = List.copyOf(sections);
        ambiguities = List.copyOf(ambiguities);
        appliedConstraints = List.copyOf(appliedConstraints);
        conversationHistory = List.copyOf(conversationHistory);
    }
}
