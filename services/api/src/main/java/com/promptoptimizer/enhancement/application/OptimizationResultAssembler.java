package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
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
        if (providerResponse == null || isBlank(providerResponse.provider()) || isBlank(providerResponse.model())) {
            throw invalidResponse("模型响应缺少 Provider 元数据");
        }
        Map<PromptSectionType, PromptSection> sections = collectSections(providerResponse.sections());
        if (!sections.keySet().containsAll(REQUIRED_TYPES)) {
            throw invalidResponse("模型响应缺少必需的提示词段落");
        }

        appendConfirmedAnswers(sections, planAnswers);
        appendConstraints(sections, constraints);
        List<String> assessed = resolveAmbiguities(providerResponse, sections, ambiguities);
        List<String> remainingAmbiguities = planConfirmed ? List.of() : assessed;
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
                Math.max(0, latencyMs)
        );
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
        return findings.stream().map(String::trim)
                .filter(value -> !GENERIC_WARNINGS.contains(value.replaceAll("[。.!！]+$", "")))
                .distinct().toList();
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
