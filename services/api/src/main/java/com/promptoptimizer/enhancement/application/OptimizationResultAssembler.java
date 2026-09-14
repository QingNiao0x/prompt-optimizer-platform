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
        if (planConfirmed) {
            sections.remove(PromptSectionType.CLARIFICATIONS);
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
        List<String> remainingAmbiguities = planConfirmed ? List.of() : List.copyOf(ambiguities);
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
