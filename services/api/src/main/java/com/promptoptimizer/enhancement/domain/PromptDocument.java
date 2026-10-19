package com.promptoptimizer.enhancement.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import java.util.List;
import java.util.UUID;

/**
 * V2 优化结果：TEXT 的唯一正文是 optimizedPrompt，SECTIONS 保留旧结果的真实段落。
 * 链路版本由服务端确定，不从用户偏好或正文标题推断，也不为自然正文伪造四要素段落。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptDocument(
        String optimizedPrompt,
        ResultKind resultKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PromptSection> sections,
        ContextSnapshot contextReport,
        List<String> ambiguities,
        List<String> appliedConstraints,
        TemplateCode templateCode,
        ProviderMetadata provider,
        long latencyMs,
        List<String> warnings,
        List<PlanningFactCard> evidenceCards,
        PromptFormat promptFormat,
        ConstraintBundle constraintGroups,
        String generationPipeline,
        String chainVersion,
        String framework,
        UUID recordId,
        long editRevision,
        @com.fasterxml.jackson.annotation.JsonIgnore List<PlanAnswer> confirmedPlanAnswers
) {
    /** 自然正文链路标识；与排版偏好独立，旧COMPACT不能据此升级链路。 */
    public static final String NATURAL_PIPELINE = "NATURAL";
    /** 保留旧生成实现的链路标识，V1及旧历史继续使用。 */
    public static final String LEGACY_PIPELINE = "LEGACY";
    /** 逐字保留旧输出的适配版本。 */
    public static final String LEGACY_VERSION = "1";
    /** 五模块投影版本；独立未决和示例分别保留在背景及输出模块内。 */
    public static final String STRUCTURED_VERSION = "2";
    /** 结果形态是协议值，不承担任务分类或确认状态。 */
    public enum ResultKind { TEXT, SECTIONS }

    public PromptDocument {
        if (optimizedPrompt == null || optimizedPrompt.isBlank()) throw new IllegalArgumentException("优化正文不能为空");
        if (resultKind == null) throw new IllegalArgumentException("结果形态不能为空");
        sections = resultKind == ResultKind.TEXT ? null : List.copyOf(sections);
        ambiguities = List.copyOf(ambiguities);
        appliedConstraints = List.copyOf(appliedConstraints);
        warnings = List.copyOf(warnings);
        evidenceCards = List.copyOf(evidenceCards);
        confirmedPlanAnswers = confirmedPlanAnswers == null ? null : List.copyOf(confirmedPlanAnswers);
    }

    /** 非Plan和旧结果无需携带内部题答；字段不进入V2响应，避免出现第二份可编辑事实来源。 */
    public PromptDocument(String optimizedPrompt, ResultKind resultKind, List<PromptSection> sections,
            ContextSnapshot contextReport, List<String> ambiguities, List<String> appliedConstraints,
            TemplateCode templateCode, ProviderMetadata provider, long latencyMs, List<String> warnings,
            List<PlanningFactCard> evidenceCards, PromptFormat promptFormat, ConstraintBundle constraintGroups,
            String generationPipeline, String chainVersion, String framework, UUID recordId, long editRevision) {
        this(optimizedPrompt, resultKind, sections, contextReport, ambiguities, appliedConstraints, templateCode,
                provider, latencyMs, warnings, evidenceCards, promptFormat, constraintGroups, generationPipeline,
                chainVersion, framework, recordId, editRevision, null);
    }

    /** 旧链结果只做协议适配，正文、真实段落和实际模式逐字保留。 */
    public static PromptDocument legacy(OptimizationResult result) {
        return new PromptDocument(result.optimizedPrompt(), ResultKind.SECTIONS, result.sections(),
                result.contextReport(), result.ambiguities(), result.appliedConstraints(), result.templateCode(),
                result.provider(), result.latencyMs(), result.warnings(), result.evidenceCards(),
                result.promptFormat(), result.constraintGroups(), LEGACY_PIPELINE, LEGACY_VERSION, null, null, 0);
    }

    /** 历史关闭时ID为空；仅返回实际保存的ID，不生成虚构记录引用。 */
    public PromptDocument withRecordId(UUID id) {
        return new PromptDocument(optimizedPrompt, resultKind, sections, contextReport, ambiguities, appliedConstraints,
                templateCode, provider, latencyMs, warnings, evidenceCards, promptFormat, constraintGroups,
                generationPipeline, chainVersion, framework, id, editRevision, confirmedPlanAnswers);
    }

    /** V2显式结构化契约采用五个标准模块；只整理已有内容，空背景不编造项目事实。 */
    public static PromptDocument structured(OptimizationResult result) {
        var content = new java.util.EnumMap<PromptSectionType, String>(PromptSectionType.class);
        result.sections().forEach(section -> content.merge(section.type(), section.content(), (a, b) -> a + "\n\n" + b));
        String background = content.getOrDefault(PromptSectionType.BACKGROUND, "");
        // 五模块是呈现契约；已有独立未决条件必须保留，不能因没有第六模块而静默丢失。
        if (content.containsKey(PromptSectionType.CLARIFICATIONS)) {
            background += "\n\n待确认事项：\n" + content.get(PromptSectionType.CLARIFICATIONS);
        }
        String output = content.getOrDefault(PromptSectionType.OUTPUT, "");
        if (content.containsKey(PromptSectionType.EXAMPLES)) output += "\n\n示例要求：\n" + content.get(PromptSectionType.EXAMPLES);
        List<PromptSection> sections = List.of(
                standard(PromptSectionType.BACKGROUND, "背景", background.strip(),
                        "仅依据原始提示词与本次授权材料中的已知背景，不编造缺失事实。"),
                standard(PromptSectionType.TASK, "任务", content.get(PromptSectionType.TASK), result.optimizedPrompt()),
                standard(PromptSectionType.OUTPUT, "输出要求", output, "交付原始提示词要求的成品，保持原定类型、格式与范围。"),
                standard(PromptSectionType.CONSTRAINTS, "约束条件", content.get(PromptSectionType.CONSTRAINTS),
                        "遵守原始需求的独立条件、事实边界和实际授权范围。"),
                standard(PromptSectionType.ACCEPTANCE, "验收标准", content.get(PromptSectionType.ACCEPTANCE),
                        "逐项核对任务、输出要求与约束条件；没有实际验证的部分明确标记，不声称已经验证。"));
        String body = sections.stream().map(section -> "## " + section.title() + "\n" + section.content())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return new PromptDocument(body, ResultKind.SECTIONS, sections, result.contextReport(), result.ambiguities(),
                result.appliedConstraints(), result.templateCode(), result.provider(), result.latencyMs(), result.warnings(),
                result.evidenceCards(), PromptFormat.STRUCTURED, result.constraintGroups(), LEGACY_PIPELINE, STRUCTURED_VERSION, null, null, 0);
    }

    private static PromptSection standard(PromptSectionType type, String title, String value, String fallback) {
        return new PromptSection(type, title, value == null || value.isBlank() ? fallback : value);
    }
}
