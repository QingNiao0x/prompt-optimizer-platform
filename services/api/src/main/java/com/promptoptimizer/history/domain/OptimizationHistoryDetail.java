package com.promptoptimizer.history.domain;

import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.policy.domain.ConstraintBundle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 当前租户/工作区的历史详情；modelName 保留调用 ID，modelVersion 使用当次版本快照，旧记录可为空。
 * 包含原始提示词、优化结果、脱敏上下文摘要和增强选项，不回填当前目录版本。
 * constraintGroups 仅恢复当次已保存的服务端来源；旧记录可为空，不重新推断或修改正文。
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
        List<ConversationMessage> conversationHistory,
        Map<String, Object> permissionPolicy,
        String modelVersion,
        com.promptoptimizer.enhancement.domain.PromptFormat promptFormat,
        ConstraintBundle constraintGroups,
        String resultKind,
        String generationPipeline,
        String chainVersion,
        String framework,
        com.promptoptimizer.enhancement.domain.NaturalProcessing processing
) {

    /**
     * 对列表字段做防御性拷贝。
     */
    public OptimizationHistoryDetail {
        sections = List.copyOf(sections);
        ambiguities = List.copyOf(ambiguities);
        appliedConstraints = List.copyOf(appliedConstraints);
        conversationHistory = List.copyOf(conversationHistory);
        promptFormat = promptFormat == null ? com.promptoptimizer.enhancement.domain.PromptFormat.STRUCTURED : promptFormat;
    }

    /** 旧历史与调用方缺少处理阶段时保持未知，不从正文相同或模型身份反推。 */
    public OptimizationHistoryDetail(UUID id, String rawPrompt, String optimizedPrompt, List<PromptSection> sections,
            Map<String,Object> contextSummary, List<String> ambiguities, List<String> appliedConstraints,
            String templateCode, String providerName, String modelName, boolean mock, Integer latencyMs,
            OffsetDateTime createdAt, boolean includePermissionBoundaries,
            List<ConversationMessage> conversationHistory, Map<String,Object> permissionPolicy, String modelVersion,
            com.promptoptimizer.enhancement.domain.PromptFormat promptFormat, ConstraintBundle constraintGroups,
            String resultKind, String generationPipeline, String chainVersion, String framework) {
        this(id, rawPrompt, optimizedPrompt, sections, contextSummary, ambiguities, appliedConstraints, templateCode,
                providerName, modelName, mock, latencyMs, createdAt, includePermissionBoundaries, conversationHistory,
                permissionPolicy, modelVersion, promptFormat, constraintGroups, resultKind, generationPipeline, chainVersion, framework, null);
    }

    /** 兼容旧构造与旧历史；缺少链路信息的COMPACT依然属于旧链。 */
    public OptimizationHistoryDetail(UUID id, String rawPrompt, String optimizedPrompt, List<PromptSection> sections,
            Map<String,Object> contextSummary, List<String> ambiguities, List<String> appliedConstraints,
            String templateCode, String providerName, String modelName, boolean mock, Integer latencyMs,
            OffsetDateTime createdAt, boolean includePermissionBoundaries,
            List<ConversationMessage> conversationHistory, Map<String,Object> permissionPolicy, String modelVersion,
            com.promptoptimizer.enhancement.domain.PromptFormat promptFormat, ConstraintBundle constraintGroups) {
        this(id, rawPrompt, optimizedPrompt, sections, contextSummary, ambiguities, appliedConstraints, templateCode,
                providerName, modelName, mock, latencyMs, createdAt, includePermissionBoundaries,
                conversationHistory, permissionPolicy, modelVersion, promptFormat, constraintGroups, "SECTIONS", "LEGACY", "1", null);
    }

    /** 旧历史未保存来源分组时保持未知；不修改保存的正文，也不猜测规则来源。 */
    public OptimizationHistoryDetail(UUID id, String rawPrompt, String optimizedPrompt, List<PromptSection> sections,
            Map<String,Object> contextSummary, List<String> ambiguities, List<String> appliedConstraints,
            String templateCode, String providerName, String modelName, boolean mock, Integer latencyMs,
            OffsetDateTime createdAt, boolean includePermissionBoundaries,
            List<ConversationMessage> conversationHistory, Map<String,Object> permissionPolicy, String modelVersion,
            com.promptoptimizer.enhancement.domain.PromptFormat promptFormat) {
        this(id, rawPrompt, optimizedPrompt, sections, contextSummary, ambiguities, appliedConstraints,
                templateCode, providerName, modelName, mock, latencyMs, createdAt, includePermissionBoundaries,
                conversationHistory, permissionPolicy, modelVersion, promptFormat, null);
    }

    /** 旧历史没有展示形式时继续分节；保存的最终正文不重新渲染。 */
    public OptimizationHistoryDetail(UUID id, String rawPrompt, String optimizedPrompt, List<PromptSection> sections,
            Map<String,Object> contextSummary, List<String> ambiguities, List<String> appliedConstraints,
            String templateCode, String providerName, String modelName, boolean mock, Integer latencyMs,
            OffsetDateTime createdAt, boolean includePermissionBoundaries,
            List<ConversationMessage> conversationHistory, Map<String,Object> permissionPolicy, String modelVersion) {
        this(id, rawPrompt, optimizedPrompt, sections, contextSummary, ambiguities, appliedConstraints,
                templateCode, providerName, modelName, mock, latencyMs, createdAt, includePermissionBoundaries,
                conversationHistory, permissionPolicy, modelVersion,
                com.promptoptimizer.enhancement.domain.PromptFormat.STRUCTURED);
    }
}
