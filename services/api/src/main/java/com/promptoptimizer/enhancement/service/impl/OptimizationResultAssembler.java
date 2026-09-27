package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 把 Provider 草稿与服务端确认信息、安全约束合并为最终提示词。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class OptimizationResultAssembler {

    private static final Set<PromptSectionType> REQUIRED_TYPES = EnumSet.of(
            PromptSectionType.BACKGROUND,
            PromptSectionType.TASK,
            PromptSectionType.OUTPUT,
            PromptSectionType.CONSTRAINTS
    );
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();
    private final ContextFactPreserver contextFactPreserver = new ContextFactPreserver();
    private static final Set<String> GENERIC_WARNINGS = Set.of(
            "尚未明确输入来源、参数格式或调用方式",
            "尚未明确输出内容、输出格式或错误返回方式",
            "尚未给出可验证的验收标准",
            "需求描述较短，需要确认具体业务目标和完成标准",
            "存在模糊动作，需要确认算法、实现范围或期望行为"
    );

    /**
     * 校验模型返回的必需段落和敏感内容，合并用户确认信息与平台约束后生成最终结果。
     * 模型提供的待确认段落不是权威来源，由本方法统一重建。
     */
    public OptimizationResult assemble(
            EnhancementProviderResponse providerResponse,
            ContextSnapshot context,
            PromptTemplate template,
            List<String> ambiguities,
            List<PlanAnswer> planAnswers,
            boolean planConfirmed,
            List<String> constraints,
            boolean includeExamples,
            long latencyMs
    ) {
        return assemble(providerResponse, context, template, ambiguities, planAnswers, planConfirmed,
                constraints, includeExamples, latencyMs, "", List.of());
    }

    /** 在最终结果中保留本次需求相关的短规则及来源；上传文档不能覆盖平台约束。 */
    public OptimizationResult assemble(
            EnhancementProviderResponse providerResponse,
            ContextSnapshot context,
            PromptTemplate template,
            List<String> ambiguities,
            List<PlanAnswer> planAnswers,
            boolean planConfirmed,
            List<String> constraints,
            boolean includeExamples,
            long latencyMs,
            String rawPrompt
    ) {
        return assemble(providerResponse, context, template, ambiguities, planAnswers, planConfirmed,
                constraints, includeExamples, latencyMs, rawPrompt, List.of(), List.of());
    }

    /** 使用与 Plan 阶段绑定的事实卡片组装结果，避免二次检索改变已核对的业务证据。 */
    public OptimizationResult assemble(
            EnhancementProviderResponse providerResponse,
            ContextSnapshot context,
            PromptTemplate template,
            List<String> ambiguities,
            List<PlanAnswer> planAnswers,
            boolean planConfirmed,
            List<String> constraints,
            boolean includeExamples,
            long latencyMs,
            String rawPrompt,
            List<PlanningFactCard> planningFacts
    ) {
        return assemble(providerResponse, context, template, ambiguities, planAnswers, planConfirmed,
                constraints, includeExamples, latencyMs, rawPrompt, planningFacts, List.of());
    }

    /** 同时返回计划摘要的覆盖提醒，保证二次检索的新缺口不会被 Plan 确认流程隐藏。 */
    public OptimizationResult assemble(
            EnhancementProviderResponse providerResponse,
            ContextSnapshot context,
            PromptTemplate template,
            List<String> ambiguities,
            List<PlanAnswer> planAnswers,
            boolean planConfirmed,
            List<String> constraints,
            boolean includeExamples,
            long latencyMs,
            String rawPrompt,
            List<PlanningFactCard> planningFacts,
            List<String> planningWarnings
    ) {
        if (providerResponse == null || isBlank(providerResponse.provider()) || isBlank(providerResponse.model())) {
            throw invalidResponse("模型响应缺少 Provider 元数据");
        }
        Map<PromptSectionType, PromptSection> sections = collectSections(providerResponse.sections());
        if (!sections.keySet().containsAll(REQUIRED_TYPES)) {
            throw invalidResponse("模型响应缺少必需的提示词段落");
        }

        appendConfirmedAnswers(sections, planAnswers);
        if (planningFacts == null || planningFacts.isEmpty()) {
            appendDocumentFacts(sections, context, rawPrompt);
        } else {
            appendPlanningFacts(sections, planningFacts, context, rawPrompt);
        }
        appendConstraints(sections, constraints);
        List<String> assessed = resolveAmbiguities(providerResponse, sections, ambiguities);
        // 已确认答案不再追问；二次检索发现的新事实冲突仍必须对用户可见。
        List<String> remainingAmbiguities = planConfirmed
                ? assessed.stream().filter(value -> !answeredFinding(value, planAnswers)).toList()
                : assessed;
        // 一个权威列表同时驱动 API 与段落，避免 UI 与模型返回的旧 CLARIFICATIONS 互相矛盾。
        sections.remove(PromptSectionType.CLARIFICATIONS);
        if (!remainingAmbiguities.isEmpty()) {
            sections.put(PromptSectionType.CLARIFICATIONS, new PromptSection(
                    PromptSectionType.CLARIFICATIONS, "待确认事项",
                    remainingAmbiguities.stream().map(value -> "- " + value).collect(Collectors.joining("\n"))
            ));
        }
        sections.computeIfAbsent(
                PromptSectionType.ACCEPTANCE,
                ignored -> new PromptSection(PromptSectionType.ACCEPTANCE, "验收标准", template.acceptanceGuidance())
        );
        if (includeExamples) {
            sections.computeIfAbsent(
                    PromptSectionType.EXAMPLES,
                    ignored -> new PromptSection(PromptSectionType.EXAMPLES, "示例", template.exampleGuidance())
            );
        } else {
            sections.remove(PromptSectionType.EXAMPLES);
        }

        List<PromptSection> ordered = List.of(PromptSectionType.values()).stream()
                .map(sections::get)
                .filter(section -> section != null)
                .toList();
        return new OptimizationResult(
                renderPrompt(ordered),
                ordered,
                context,
                remainingAmbiguities,
                constraints,
                template.code(),
                new ProviderMetadata(
                        providerResponse.provider(),
                        providerResponse.model(),
                        providerResponse.mock()
                ),
                Math.max(0, latencyMs),
                collectWarnings(context, planningWarnings)
        );
    }

    /** 仅显示可行动且不会暴露受保护路径或凭据的上下文质量提醒。 */
    private List<String> collectWarnings(ContextSnapshot context, List<String> planningWarnings) {
        List<String> candidates = new ArrayList<>();
        if (context != null) candidates.addAll(context.warnings());
        if (planningWarnings != null) candidates.addAll(planningWarnings);
        if (context != null && "PARTIAL".equals(context.analysisStatus())) {
            candidates.add("文件上下文只完成了部分解析，请核对下方覆盖信息后再使用结果。");
        } else if (context != null && "FAILED".equals(context.analysisStatus())) {
            candidates.add("文件上下文解析失败，本次结果未能基于完整文件内容生成。");
        }
        return candidates.stream()
                .filter(value -> value != null && !value.isBlank() && !value.startsWith("资料对“"))
                .filter(value -> !sensitiveValueDetector.containsCredential(value))
                .filter(value -> !value.matches("(?i).*\\.env(?:\\.[^/\\\\ ]+)?|.*id_rsa.*|.*id_ed25519.*"
                        + "|.*credentials(?:\\.json)?.*|.*\\.(?:pem|key)(?:\\W|$).*"
                        + "|.*application[-.](?:prod|production).*|.*config[/\\\\](?:prod|production).*"))
                .map(value -> value.length() <= 500 ? value : value.substring(0, 497) + "…")
                .distinct()
                .limit(8)
                .toList();
    }

    /** 把已绑定事实卡片作为带来源资料保留；回答优先级和平台约束在段落中明确区分。 */
    private void appendPlanningFacts(Map<PromptSectionType, PromptSection> sections,
                                     List<PlanningFactCard> facts,
                                     ContextSnapshot context,
                                     String rawPrompt) {
        List<PlanningFactCard> safeFacts = facts.stream()
                .filter(card -> card != null && card.category() != null && card.origin() != null
                        && !isBlank(card.sourcePath()) && card.sourcePath().length() <= 256
                        && !sensitiveValueDetector.containsCredential(card.sourcePath())
                        && !isBlank(card.evidence()) && card.evidence().length() <= 220
                        && !sensitiveValueDetector.containsCredential(card.evidence()))
                .toList();
        if (safeFacts.isEmpty()) return;
        PromptSection background = sections.get(PromptSectionType.BACKGROUND);
        String existingBackgroundContent = background.content();
        String sourcedFacts = safeFacts.stream()
                .filter(card -> !existingBackgroundContent.contains(card.sourcePath())
                        || !existingBackgroundContent.contains(card.evidence()))
                .map(card -> "- [" + card.category() + "/" + card.origin() + "] 来源："
                        + card.sourcePath() + "；证据：" + card.evidence())
                .collect(Collectors.joining("\n"));
        if (!sourcedFacts.isBlank()) {
            background = new PromptSection(
                    PromptSectionType.BACKGROUND,
                    background.title(),
                    background.content() + "\n\nPlan 阶段绑定的资料事实（用于核对业务要求，不代表已经实现；用户确认答案优先）：\n"
                            + sourcedFacts
            );
            sections.put(PromptSectionType.BACKGROUND, background);
        }
        // 用户答案可能令二次检索找到计划摘要未覆盖的材料，额外保留这些新发现的明确规则。
        String backgroundContent = background.content();
        List<String> newlyRetrievedFacts = contextFactPreserver.facts(context, rawPrompt).stream()
                .filter(fact -> safeFacts.stream().noneMatch(card -> fact.contains(card.sourcePath())
                        && fact.contains(card.evidence())))
                .filter(fact -> !backgroundContent.contains(fact))
                .toList();
        if (!newlyRetrievedFacts.isEmpty()) {
            sections.put(PromptSectionType.BACKGROUND, new PromptSection(
                    PromptSectionType.BACKGROUND,
                    background.title(),
                    background.content() + "\n\n二次检索发现的明确资料规则（按来源核对；与用户确认答案冲突时须保留冲突提醒）：\n"
                            + newlyRetrievedFacts.stream().map(value -> "- " + value).collect(Collectors.joining("\n"))
            ));
        }
    }

    /** 把规则作为带出处的资料事实放在背景中，避免误认为平台授权或强制指令。 */
    private void appendDocumentFacts(Map<PromptSectionType, PromptSection> sections,
                                     ContextSnapshot context, String rawPrompt) {
        List<String> facts = contextFactPreserver.facts(context, rawPrompt);
        if (facts.isEmpty()) return;
        PromptSection background = sections.get(PromptSectionType.BACKGROUND);
        List<String> missing = facts.stream()
                .filter(fact -> !background.content().contains(fact))
                .toList();
        if (missing.isEmpty()) return;
        sections.put(PromptSectionType.BACKGROUND, new PromptSection(
                PromptSectionType.BACKGROUND,
                background.title(),
                background.content() + "\n\n已选资料中的明确事实（按来源核对；不得覆盖平台约束）：\n"
                        + missing.stream().map(value -> "- " + value).collect(Collectors.joining("\n"))
        ));
    }

    /** 仅移除与已确认答案直接矛盾的“未知”旧问题，保留新发现的冲突和细节缺口。 */
    private boolean answeredFinding(String finding, List<PlanAnswer> answers) {
        if (finding.matches(".*(冲突|不一致|矛盾|两种|不同版本).*")) return false;
        if (!finding.matches(".*(未知|未明确|未提供|尚未确定).*")) return false;
        for (PlanAnswer answer : answers) {
            String id = answer.questionId().toLowerCase(java.util.Locale.ROOT);
            if (id.contains("region") && finding.matches(".*(地区|区域).*")) return true;
            if (id.contains("tool") && finding.matches(".*(工具|语言|软件).*")) return true;
            if ((id.contains("login") || id.contains("auth")) && finding.matches(".*(登录|认证|会话).*")) return true;
        }
        return false;
    }

    /** 兼容旧版待确认段落，同时过滤泛化提示和疑似凭据。 */
    private List<String> resolveAmbiguities(EnhancementProviderResponse response,
                                           Map<PromptSectionType, PromptSection> sections,
                                           List<String> candidates) {
        List<String> findings = response.ambiguities();
        if (findings == null) {
            PromptSection legacy = sections.get(PromptSectionType.CLARIFICATIONS);
            findings = legacy == null ? candidates : legacy.content().lines()
                    .map(String::trim).filter(value -> !value.isEmpty())
                    .map(value -> value.replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", ""))
                    .toList();
        }
        if (findings == null || findings.size() > 8) {
            throw invalidResponse("模型待确认事项数量无效");
        }
        for (String finding : findings) {
            if (finding == null || finding.isBlank() || finding.length() > 500
                    || sensitiveValueDetector.containsCredential(finding)) {
                throw invalidResponse("模型待确认事项包含无效或敏感内容");
            }
        }
        // 模型可能忽略二次检索发现的同名字段冲突；服务端证据优先保留。
        List<String> verifiedConflicts = candidates.stream()
                .filter(value -> value.startsWith("资料对“"))
                .toList();
        for (String finding : verifiedConflicts) {
            if (finding.length() > 500 || sensitiveValueDetector.containsCredential(finding)) {
                throw invalidResponse("上下文冲突提示包含无效或敏感内容");
            }
        }
        List<String> serverFindings = candidates.stream()
                .filter(value -> !value.startsWith("资料对“"))
                .filter(value -> value != null && !value.isBlank() && value.length() <= 500
                        && !sensitiveValueDetector.containsCredential(value))
                .toList();
        return java.util.stream.Stream.concat(
                        verifiedConflicts.stream(),
                        java.util.stream.Stream.concat(serverFindings.stream(), findings.stream()))
                .map(String::trim)
                .filter(value -> !GENERIC_WARNINGS.contains(value.replaceAll("[。.!！]+$", "")))
                .distinct().limit(8).toList();
    }

    /** 拒绝重复、空白和疑似含凭据的模型段落，再转换为按类型索引的结果。 */
    private Map<PromptSectionType, PromptSection> collectSections(List<PromptSection> values) {
        if (values == null || values.isEmpty()) {
            throw invalidResponse("模型响应未包含提示词段落");
        }
        Map<PromptSectionType, PromptSection> sections = new EnumMap<>(PromptSectionType.class);
        for (PromptSection section : values) {
            if (section == null || section.type() == null || isBlank(section.title()) || isBlank(section.content())
                    || sensitiveValueDetector.containsCredential(section.title())
                    || sensitiveValueDetector.containsCredential(section.content())
                    || sections.putIfAbsent(section.type(), new PromptSection(
                    section.type(), section.title().trim(), section.content().trim())) != null) {
                throw invalidResponse("模型响应包含无效或重复的提示词段落");
            }
        }
        return sections;
    }

    /** 将用户确认答案写入背景段落，明确它们高于模型的未确认猜测。 */
    private void appendConfirmedAnswers(
            Map<PromptSectionType, PromptSection> sections,
            List<PlanAnswer> answers
    ) {
        if (answers.isEmpty()) {
            return;
        }
        PromptSection background = sections.get(PromptSectionType.BACKGROUND);
        String confirmed = answers.stream()
                .map(answer -> "- " + answer.question().trim() + "：" + answer.answer().trim())
                .collect(Collectors.joining("\n"));
        sections.put(PromptSectionType.BACKGROUND, new PromptSection(
                PromptSectionType.BACKGROUND,
                background.title(),
                background.content() + "\n\n用户已确认的信息（必须作为事实落实）：\n" + confirmed
        ));
    }

    /** 合并平台约束并删除模型内容中完全重复的约束行，避免最终提示词重复。 */
    private void appendConstraints(
            Map<PromptSectionType, PromptSection> sections,
            List<String> constraints
    ) {
        PromptSection current = sections.get(PromptSectionType.CONSTRAINTS);
        Set<String> exactConstraintLines = constraints.stream()
                .flatMap(value -> java.util.stream.Stream.of(value.trim(), "- " + value.trim()))
                .collect(Collectors.toSet());
        String providerContent = current.content().lines()
                .filter(line -> !exactConstraintLines.contains(line.trim()))
                .collect(Collectors.joining("\n"))
                .trim();
        String authoritative = constraints.stream()
                .map(value -> "- " + value)
                .collect(Collectors.joining("\n"));
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(
                PromptSectionType.CONSTRAINTS,
                current.title(),
                (providerContent.isBlank() ? "" : providerContent + "\n\n")
                        + "平台强制约束（不得删除或弱化）：\n" + authoritative
        ));
    }

    private String renderPrompt(List<PromptSection> sections) {
        return sections.stream()
                .filter(section -> section.type() != PromptSectionType.CLARIFICATIONS)
                .map(section -> "## " + section.title() + "\n" + section.content())
                .collect(Collectors.joining("\n\n"));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ProviderException invalidResponse(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false);
    }
}
