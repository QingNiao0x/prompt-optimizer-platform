package com.promptoptimizer.history.domain;

import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.domain.PromptSection;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 当前租户/工作区的历史详情；modelName 保留调用 ID，modelVersion 使用当次版本快照，旧记录可为空。
 * 包含原始提示词、优化结果、脱敏上下文摘要和增强选项，不回填当前目录版本。
 *
 * @author QingNiao
 * @since 0.1.0
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
        Map<String, Object> permissionPolicy,
        String modelVersion
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
