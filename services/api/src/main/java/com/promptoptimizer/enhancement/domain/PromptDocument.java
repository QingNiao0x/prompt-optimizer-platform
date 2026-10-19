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
    /** 结构化投影版本；模块仅呈现独立内容，API未决清单与正文执行前提分别保留。 */
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

    /**
     * V2显式结构化按五类标准模块投影，只呈现有独立内容的模块，不为填槽生成通用文字。
     * 组装器已将权威未决清单的完整执行前提写入约束；不再把同一界面清单投成第二份背景。
     */
    public static PromptDocument structured(OptimizationResult result) {
        var content = new java.util.EnumMap<PromptSectionType, String>(PromptSectionType.class);
        result.sections().forEach(section -> content.merge(section.type(), section.content(), (a, b) -> a + "\n\n" + b));
        String constraints = content.getOrDefault(PromptSectionType.CONSTRAINTS, "");
        if (content.containsKey(PromptSectionType.CLARIFICATIONS)) {
            // 仅识别组装器逐项生成的同源清单；旧结果中未绑定API清单的独立文字完整保留。
            String independent = content.get(PromptSectionType.CLARIFICATIONS).lines()
                    .filter(line -> !deliveredClarification(line, result))
                    .collect(java.util.stream.Collectors.joining("\n")).strip();
            if (!independent.isBlank()) constraints += "\n\n待确认事项：\n" + independent;
        }
        String output = content.getOrDefault(PromptSectionType.OUTPUT, "");
        if (content.containsKey(PromptSectionType.EXAMPLES)) output += "\n\n示例要求：\n" + content.get(PromptSectionType.EXAMPLES);
        List<PromptSection> sections = List.of(
                standard(PromptSectionType.BACKGROUND, "背景", content.get(PromptSectionType.BACKGROUND)),
                standard(PromptSectionType.TASK, "任务", content.getOrDefault(PromptSectionType.TASK, result.optimizedPrompt())),
                standard(PromptSectionType.OUTPUT, "输出要求", output),
                standard(PromptSectionType.CONSTRAINTS, "约束条件", constraints),
                standard(PromptSectionType.ACCEPTANCE, "验收标准", content.get(PromptSectionType.ACCEPTANCE)))
                .stream().filter(section -> !section.content().isBlank()).toList();
        String body = sections.stream().map(section -> "## " + section.title() + "\n" + section.content())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return new PromptDocument(body, ResultKind.SECTIONS, sections, result.contextReport(), result.ambiguities(),
                result.appliedConstraints(), result.templateCode(), result.provider(), result.latencyMs(), result.warnings(),
                result.evidenceCards(), PromptFormat.STRUCTURED, result.constraintGroups(), LEGACY_PIPELINE, STRUCTURED_VERSION, null, null, 0);
    }

    /** 模块标题统一，内容逐字保留；空辅助模块在投影末尾省去，不影响旧历史和自然正文。 */
    private static PromptSection standard(PromptSectionType type, String title, String value) {
        return new PromptSection(type, title, value == null ? "" : value);
    }

    /**
     * 仅省去API已登记、且在执行正文有逐字完整独立行的同源提醒。
     * 不能仅因问题ID或清单存在而认定正文已覆盖；改写、条件与新对象仍保守保留。
     */
    private static boolean deliveredClarification(String line, OptimizationResult result) {
        String statement = line.strip().replaceFirst("^-\\s+", "");
        return result.ambiguities().contains(statement) && result.sections().stream()
                .filter(section -> section.type() == PromptSectionType.TASK
                        || section.type() == PromptSectionType.OUTPUT || section.type() == PromptSectionType.CONSTRAINTS)
                // 引文、代码或具名子节中的相同文字不作为本次无条件执行覆盖证据。
                .filter(section -> section.content().lines().noneMatch(candidate -> candidate.strip()
                        .matches("^(?:`{3,}|~{3,}|>|#{1,6}\\s|\\*\\*|__).*")
                        || candidate.startsWith("    ") || candidate.startsWith("\t")))
                .anyMatch(section -> section.content().lines()
                        .anyMatch(candidate -> candidate.strip().replaceFirst("^-\\s+", "").equals(statement)));
    }
}
