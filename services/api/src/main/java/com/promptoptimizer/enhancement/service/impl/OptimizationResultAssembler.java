package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.AmbiguityReference;
import com.promptoptimizer.common.logging.LogFields;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(OptimizationResultAssembler.class);
    private static final Set<PromptSectionType> REQUIRED_TYPES = EnumSet.of(
            PromptSectionType.BACKGROUND,
            PromptSectionType.TASK,
            PromptSectionType.OUTPUT,
            PromptSectionType.CONSTRAINTS
    );
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();
    private final ContextFactPreserver contextFactPreserver = new ContextFactPreserver();
    private final RequirementFidelityGuard fidelityGuard = new RequirementFidelityGuard();
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
            throw invalidResponse(Reason.METADATA_INVALID, "provider");
        }
        Map<PromptSectionType, PromptSection> sections = collectSections(providerResponse.sections());
        if (!sections.keySet().containsAll(REQUIRED_TYPES)) {
            throw invalidResponse(Reason.REQUIRED_SECTIONS_MISSING, "sections");
        }

        ConfirmedDecisionSet decisions = ConfirmedDecisionSet.from(planAnswers);
        var resolvedState = ResolvedPlanState.from(planConfirmed ? decisions : ConfirmedDecisionSet.from(List.of()), rawPrompt);
        // 先建立有效执行视图，再提取规则；原始提示词与卡片仍完整保存，旧未知不能被保真流程补回。
        String effectiveRawPrompt = resolvedState.reconcile(rawPrompt);
        sections.replaceAll((type, section) -> new PromptSection(type, section.title(), resolvedState.reconcile(section.content())));
        String evidenceQuery = decisions.retrievalQuery(rawPrompt);
        List<PlanningFactCard> eligibleFacts = new PlanningFactCardExtractor()
                .filterBoundFacts(planningFacts, context, evidenceQuery);
        List<String> documentFacts = contextFactPreserver.facts(context, evidenceQuery);
        List<String> explicitRules = fidelityGuard.explicitRules(effectiveRawPrompt, decisions.decisions());
        var sourceObjects = SourceObjectContract.from(effectiveRawPrompt, context, eligibleFacts,
                planConfirmed ? decisions.knownDecisions() : List.of());
        sections.forEach((type, section) -> sourceObjects.validate(section.content(), "sections." + type));
        validateExecutionRules(sections, explicitRules, eligibleFacts, documentFacts);
        boolean minimal = MinimalPromptOrganizer.organize(sections, effectiveRawPrompt,
                planConfirmed ? decisions : ConfirmedDecisionSet.from(List.of()));
        appendConfirmedAnswers(sections, decisions);
        appendConfirmedDecisions(sections, decisions);
        if (eligibleFacts.isEmpty()) {
            appendDocumentFacts(sections, documentFacts);
        } else {
            appendPlanningFacts(sections, eligibleFacts, documentFacts);
        }
        appendExplicitRules(sections, explicitRules);
        appendConstraints(sections, constraints);
        if (!sourceObjects.guidance().isBlank()) appendConstraintBlock(sections, "资料对象与版本", List.of(sourceObjects.guidance()));
        String attributionDelivery = sourceObjects.deliveryGuidance(template.deliveryProfile());
        if (!attributionDelivery.isBlank()) {
            PromptSection output = sections.get(PromptSectionType.OUTPUT);
            // 与生成时使用的交付契约一致；用户复制正文后仍能看到格级边界，不依赖页面提醒。
            if (!output.content().contains(attributionDelivery)) sections.put(PromptSectionType.OUTPUT,
                    new PromptSection(output.type(), output.title(), output.content() + "\n\n" + attributionDelivery));
        }
        var readOnlyComparison = ReadOnlyMaterialComparison.from(effectiveRawPrompt);
        if (!readOnlyComparison.guidance().isBlank()) {
            appendConstraintBlock(sections, "已确定的资料比较", List.of(readOnlyComparison.guidance()));
        }
        String bodyFacts = NewsBodyFactContract.guidance(effectiveRawPrompt);
        if (!bodyFacts.isBlank()) {
            PromptSection output = sections.get(PromptSectionType.OUTPUT);
            sections.put(PromptSectionType.OUTPUT, new PromptSection(output.type(), output.title(),
                    output.content() + "\n\n" + bodyFacts));
        }
        String lengthGuidance = minimal ? NewsLengthContract.conciseGuidance(effectiveRawPrompt)
                : NewsLengthContract.guidance(effectiveRawPrompt);
        if (!lengthGuidance.isBlank()) {
            PromptSection output = sections.get(PromptSectionType.OUTPUT);
            sections.put(PromptSectionType.OUTPUT, new PromptSection(output.type(), output.title(),
                    output.content() + "\n\n" + lengthGuidance));
        }
        List<String> assessed = resolveAmbiguities(providerResponse, sections, ambiguities).stream()
                .filter(finding -> !TaskQuestionScope.unrelatedEngineeringReminder(finding, rawPrompt))
                .filter(finding -> !readOnlyComparison.directedReminder(finding)).toList();
        if (!planConfirmed) {
            // 用户在原文明确列出的未决问题不能被模型返回的空数组抹掉；绑定 Plan 的旧标签不在此重新引入。
            List<String> providerFindings = assessed;
            assessed = java.util.stream.Stream.concat(assessed.stream(), declaredPendingQuestions(rawPrompt).stream()
                            .filter(question -> providerFindings.stream().noneMatch(finding -> coversDeclaredQuestion(finding, question, rawPrompt))))
                    .distinct().toList();
        }
        assessed = classifyFindings(sections, assessed, rawPrompt, decisions, eligibleFacts, documentFacts, context);
        List<AmbiguityReference> references = normalizeAmbiguityReferences(providerResponse);
        // 先登记新冲突和绑定的未决问题，再归并模型提醒，避免重复项挤占展示预算。
        // 直接增强也可合并相同来源和取值的冲突；仅服务端绑定的 Plan 答案可消除旧问题。
        var merged = new PlanAmbiguityMerger(planConfirmed ? decisions : ConfirmedDecisionSet.from(List.of()), rawPrompt)
                .merge(assessed, ambiguities, references);
        List<String> remainingAmbiguities = merged.messages();
        List<String> resultWarnings = new ArrayList<>(collectWarnings(context, planningWarnings));
        if (merged.omittedCount() > 0) {
            resultWarnings.add("待确认事项已去重，本次展示前 8 项，另有 " + merged.omittedCount()
                    + " 项未展示；所有未决条件已完整保留在约束的执行前须确认部分，请核对后再交付执行。");
        }
        removeRepeatedProviderPrerequisites(sections, merged.executionPrerequisites(), decisions);
        appendExecutionPrerequisites(sections, merged.executionPrerequisites());
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

        ExecutionRuleCompactor.compact(sections);
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
                resultWarnings,
                evidenceCards(eligibleFacts, documentFacts)
        );
    }

    /** 只读取用户明确标记的当前待定问题，保留完整问题而不从关键词缺失制造泛化警告。 */
    private List<String> declaredPendingQuestions(String rawPrompt) {
        if (rawPrompt == null || rawPrompt.isBlank()) return List.of();
        List<String> questions = new ArrayList<>();
        boolean pendingSection = false;
        for (String line : rawPrompt.lines().toList()) {
            String value = line.strip();
            if (value.startsWith("#")) {
                pendingSection = value.matches("^#{1,6}\\s*(?:尚待明确|待确认事项|未决事项|待明确)(?:[：:]?)$");
                continue;
            }
            if (!pendingSection) continue;
            value = value.replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
            int end = Math.max(value.indexOf('？'), value.indexOf('?'));
            if (end < 2 || end >= 500 || !value.matches(".*(?:尚未决定|未确定|尚未确定|未明确|暂不确定).*")) continue;
            String question = value.substring(0, end + 1);
            if (!sensitiveValueDetector.containsCredential(question)) questions.add(question);
        }
        return List.copyOf(questions);
    }

    /**
     * 仅避免补回已被完整问题覆盖的原文条目，不删除或截短模型提醒。
     * 同一完整条件允许“该候选应如何处理”等语法差异；新条件留在独立条目中，新补充仍随原模型提醒交付。
     */
    private boolean coversDeclaredQuestion(String finding, String question, String rawPrompt) {
        int end = Math.max(finding.indexOf('？'), finding.indexOf('?'));
        if (end < 2) return false;
        String observed = finding.substring(0, end + 1).replaceAll("（例如[：:][^）]*）", "");
        String expected = question;
        if (rawPrompt.contains("当前用户所属地区条件")) {
            observed = observed.replace("当前用户所属地区条件", "当前地区条件");
        }
        // 只规范化完整处理问句的尾部，不去掉条件、业务对象、否定或数值。
        String suffix = "时[，,](?:该候选)?应(?:如何|怎样)处理(?:这条候选)?[？?]$";
        observed = observed.replaceAll(suffix, "时：候选处理？");
        expected = expected.replaceAll(suffix, "时：候选处理？");
        return observed.replaceAll("\\s", "").equals(expected.replaceAll("\\s", ""));
    }

    /**
     * 仅去掉模型约束中可证明与权威未决清单相同的整行；带新条件或解释的整行原样保留。
     * 不删除段落或靠主题词裁剪正文，避免复制时丢失新冲突和执行限制。
     */
    private void removeRepeatedProviderPrerequisites(Map<PromptSectionType, PromptSection> sections,
                                                     List<String> prerequisites, ConfirmedDecisionSet decisions) {
        if (prerequisites.isEmpty()) return;
        PromptSection section = sections.get(PromptSectionType.CONSTRAINTS);
        String cleaned = section.content().lines().filter(line -> {
            String value = line.strip().replaceFirst("^[-*•]\\s+", "");
            return !prerequisites.contains(value) && decisions.decisions().stream()
                    .filter(decision -> decision.scope() == Scope.UNRESOLVED)
                    .noneMatch(decision -> PlanDecisionIdentity.repeatsReminder(value, decision.question()));
        }).collect(Collectors.joining("\n")).strip();
        sections.put(section.type(), new PromptSection(section.type(), section.title(), cleaned));
    }

    /** 详细分类和原证据单独返回供展开核对；正文仍保留规则、定位路径及所有执行前提。 */
    private List<PlanningFactCard> evidenceCards(List<PlanningFactCard> facts, List<String> documentFacts) {
        List<PlanningFactCard> values = new ArrayList<>(facts);
        int sequence = 0;
        for (String documentFact : documentFacts) {
            int split = documentFact.indexOf('：');
            if (split < 1) continue;
            String path = documentFact.substring(0, split);
            String evidence = documentFact.substring(split + 1);
            if (values.stream().anyMatch(card -> card.sourcePath().equals(path) && card.evidence().equals(evidence))) continue;
            values.add(new PlanningFactCard("D" + (++sequence), PlanningFactCategory.BUSINESS_RULE,
                    PlanningEvidencePolicy.origin(path, "text"), path, evidence));
        }
        return values.stream().filter(card -> !isBlank(card.sourcePath()) && card.sourcePath().length() <= 256
                && !isBlank(card.evidence()) && card.evidence().length() <= 220
                && !sensitiveValueDetector.containsCredential(card.sourcePath())
                && !sensitiveValueDetector.containsCredential(card.evidence())).toList();
    }

    /**
     * 先校验模型的执行断言，再追加权威原句；否则一份正文可能同时要求保留和清空同一字段。
     * 背景允许引用历史规则用于对照；测试样例与互相冲突的资料不升级为本次执行要求。
     */
    private void validateExecutionRules(Map<PromptSectionType, PromptSection> sections, List<String> explicitRules,
                                        List<PlanningFactCard> facts, List<String> documentFacts) {
        List<String> sourceRules = new ArrayList<>();
        facts.stream().filter(card -> card.category() == PlanningFactCategory.BUSINESS_RULE)
                .filter(card -> card.origin() == PlanningFactOrigin.PROJECT_SOURCE
                        || card.origin() == PlanningFactOrigin.PROJECT_DOCUMENT
                        || card.origin() == PlanningFactOrigin.USER_MATERIAL)
                .forEach(card -> sourceRules.addAll(fidelityGuard.explicitRules(card.evidence(), List.of())));
        documentFacts.stream()
                .map(fact -> fact.substring(fact.indexOf('：') + 1))
                .forEach(fact -> sourceRules.addAll(fidelityGuard.explicitRules(fact, List.of())));
        List<String> rules = new ArrayList<>(explicitRules);
        rules.addAll(fidelityGuard.compatibleSourceRules(sourceRules, explicitRules));
        var eligibilityGuard = PlanEligibilityGuard.from(rules);
        var ruleValidation = fidelityGuard.prepare(rules);
        // 背景也不能把资料未说明的能力写成不存在；执行反转校验仍限于执行段落。
        EvidenceStateGuard.validate(sections.get(PromptSectionType.BACKGROUND).content(), rules, "sections.BACKGROUND");
        for (PromptSectionType type : List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT,
                PromptSectionType.CONSTRAINTS, PromptSectionType.ACCEPTANCE)) {
            PromptSection section = sections.get(type);
            if (section != null) {
                ruleValidation.validate(section.content(), "sections." + type);
                eligibilityGuard.validate(section.content(), "", "sections." + type);
            }
        }
    }

    /** 只补未完整覆盖的明确原句，条件、例外与否定一起保留；权限红线继续独立强制追加。 */
    private void appendExplicitRules(Map<PromptSectionType, PromptSection> sections, List<String> rules) {
        String executionText = List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT,
                        PromptSectionType.CONSTRAINTS, PromptSectionType.ACCEPTANCE).stream()
                .filter(sections::containsKey).map(type -> sections.get(type).content())
                .collect(Collectors.joining("\n"));
        List<String> missing = rules.stream().filter(rule -> !fidelityGuard.containsRule(executionText, rule)).toList();
        appendConstraintBlock(sections, "用户明确规则（须遵守平台权限边界）", missing);
    }

    /** 未决与冲突必须随可复制正文交付；不把暂不确定转换成模型自行选择的许可。 */
    private void appendExecutionPrerequisites(Map<PromptSectionType, PromptSection> sections, List<String> prerequisites) {
        appendConstraintBlock(sections, "执行前须确认（仅涉及下列未决条件的步骤需等待确认；不得自行假定答案）", prerequisites);
    }

    /** 已核实规则和代码核查仍随复制正文交付，但不混入用户待定列表；新冲突保持原分类。 */
    private List<String> classifyFindings(Map<PromptSectionType, PromptSection> sections, List<String> findings,
                                        String rawPrompt, ConfirmedDecisionSet decisions,
                                        List<PlanningFactCard> facts, List<String> documentFacts, ContextSnapshot context) {
        List<String> evidence = new ArrayList<>();
        evidence.add(rawPrompt == null ? "" : rawPrompt);
        decisions.decisions().forEach(decision -> evidence.add(decision.scope() == Scope.UNRESOLVED
                ? PlanAnswerSemantics.confirmedPart(decision.answer()) : decision.answer()));
        facts.stream().filter(fact -> fact.origin() == PlanningFactOrigin.PROJECT_SOURCE
                        || fact.origin() == PlanningFactOrigin.PROJECT_DOCUMENT || fact.origin() == PlanningFactOrigin.USER_MATERIAL)
                .forEach(fact -> evidence.add(fact.evidence()));
        documentFacts.forEach(fact -> evidence.add(fact.substring(fact.indexOf('：') + 1)));
        // 事实卡片有数量上限；分类继续核对本次实际接收且用途合格的正文，不把测试和无关材料升级为执行规则。
        var policy = new PlanningEvidencePolicy(decisions.retrievalQuery(rawPrompt));
        var eligibleFiles = context.fileSnippets().stream().filter(policy::allows)
                .filter(file -> Set.of(PlanningFactOrigin.PROJECT_SOURCE, PlanningFactOrigin.PROJECT_DOCUMENT,
                        PlanningFactOrigin.USER_MATERIAL).contains(PlanningEvidencePolicy.origin(file.path(), file.language())))
                .toList();
        eligibleFiles.stream().flatMap(file -> policy.evidenceLines(file).stream())
                .filter(policy::relevantBusinessContent).forEach(evidence::add);
        List<String> exclusionEvidence = new ArrayList<>(evidence);
        // 排除用途是接收文件的可核对元数据，不把测试正文升级为业务证据；新条件不能靠文件名被过滤。
        context.fileSnippets().stream()
                .filter(file -> PlanningEvidencePolicy.origin(file.path(), file.language()) == PlanningFactOrigin.TEST_FIXTURE)
                .filter(file -> file.content().lines().anyMatch(line -> line.strip().startsWith("仅用于排版和来源识别"))
                        && file.content().contains("不是本题事实"))
                .forEach(file -> exclusionEvidence.add("已排除的排版测试资料：" + file.path()));
        eligibleFiles.stream().map(file -> String.join("。", policy.evidenceLines(file)))
                .filter(policy::relevantBusinessContent).forEach(exclusionEvidence::add);
        var classifier = new PlanFindingClassifier();
        List<String> pending = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        List<String> known = new ArrayList<>();
        for (String finding : findings) {
            switch (classifier.classify(finding, evidence, exclusionEvidence)) {
                case UNRESOLVED -> pending.add(finding);
                case KNOWN_RULE -> known.add(finding);
                case IMPLEMENTATION_CHECK -> checks.add(finding);
            }
        }
        appendConstraintBlock(sections, "已明确的执行规则", known);
        if (!checks.isEmpty()) {
            var task = sections.get(PromptSectionType.TASK);
            sections.put(PromptSectionType.TASK, new PromptSection(task.type(), task.title(), task.content()
                    + "\n\n执行时核查现有实现（不据此臆造实现或更改业务规则）：\n- " + String.join("\n- ", checks)));
        }
        return List.copyOf(pending);
    }

    /** 修改同一份结构化约束，保证正文、编辑、复制、历史与再次增强使用一致的内容。 */
    private void appendConstraintBlock(Map<PromptSectionType, PromptSection> sections, String title, List<String> values) {
        if (values.isEmpty()) return;
        PromptSection section = sections.get(PromptSectionType.CONSTRAINTS);
        var missing = values.stream().distinct().filter(value -> !section.content().contains(value)).toList();
        if (missing.isEmpty()) return;
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS,
                section.title(), section.content() + "\n\n" + title + "：\n"
                + missing.stream().map(value -> "- " + value).collect(Collectors.joining("\n"))));
    }

    /** 仅显示可行动且不会暴露受保护路径或凭据的上下文质量提醒。 */
    private List<String> collectWarnings(ContextSnapshot context, List<String> planningWarnings) {
        List<String> candidates = new ArrayList<>();
        if (context != null) candidates.addAll(context.warnings());
        if (planningWarnings != null) candidates.addAll(planningWarnings);
        if (context != null && "PARTIAL".equals(context.analysisStatus())
                && context.warnings().stream().noneMatch(value -> value != null && value.matches(
                "(?s).*(?:部分文件未完成解析|文件解析失败|仅解析部分文件|文件上下文只完成了部分解析).*"))) {
            candidates.add("文件上下文只完成了部分解析，请核对下方覆盖信息后再使用结果。");
        } else if (context != null && "FAILED".equals(context.analysisStatus())) {
            candidates.add("文件上下文解析失败，本次结果未能基于完整文件内容生成。");
        }
        var unique = candidates.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .filter(value -> !value.startsWith("资料对“"))
                .filter(value -> !sensitiveValueDetector.containsCredential(value))
                .filter(value -> !value.matches("(?i).*\\.env(?:\\.[^/\\\\ ]+)?|.*id_rsa.*|.*id_ed25519.*"
                        + "|.*credentials(?:\\.json)?.*|.*\\.(?:pem|key)(?:\\W|$).*"
                        + "|.*application[-.](?:prod|production).*|.*config[/\\\\](?:prod|production).*"))
                .map(value -> value.length() <= 500 ? value : value.substring(0, 497) + "…")
                // 不同阶段对同一警告只改了空白或句末标点时，沿用最早的可操作表述。
                .collect(Collectors.toMap(value -> java.text.Normalizer.normalize(value,
                                java.text.Normalizer.Form.NFKC).replaceAll("\\s+", "")
                                .replaceAll("[。！？!]+$", ""),
                        value -> value, (first, ignored) -> first, java.util.LinkedHashMap::new));
        return unique.values().stream().limit(8).toList();
    }

    /** 把已绑定事实卡片作为带来源资料保留；回答优先级和平台约束在段落中明确区分。 */
    private void appendPlanningFacts(Map<PromptSectionType, PromptSection> sections,
                                     List<PlanningFactCard> facts,
                                     List<String> documentFacts) {
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
        String existingExecution = sections.values().stream().map(PromptSection::content).collect(Collectors.joining("\n"));
        String sourcedFacts = safeFacts.stream()
                .filter(card -> !existingBackgroundContent.contains(card.sourcePath())
                        || !existingBackgroundContent.contains(card.evidence()))
                // 详细出处继续由 evidenceCards 返回；复制正文已完整包含原句时无需再抄一份。
                .filter(card -> !existingExecution.contains(card.evidence()))
                .map(card -> "- " + card.sourcePath() + "：" + card.evidence())
                .collect(Collectors.joining("\n"));
        if (!sourcedFacts.isBlank()) {
            background = new PromptSection(
                    PromptSectionType.BACKGROUND,
                    background.title(),
                    background.content() + "\n\nPlan 阶段绑定的资料事实与二次检索补充（现状与目标须分别核对，不代表已经实现）：\n"
                            + sourcedFacts
            );
            sections.put(PromptSectionType.BACKGROUND, background);
        }
        // 用户答案可能令二次检索找到计划摘要未覆盖的材料，额外保留这些新发现的明确规则。
        String backgroundContent = background.content();
        List<String> newlyRetrievedFacts = documentFacts.stream()
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
                                     List<String> facts) {
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

    /**
     * 其他 Provider 也可能返回错误关联，因此应用层再次收敛；仅丢弃辅助关联，保留已校验的正文。
     * 不接收模型自报的确认状态，不将关联正文、问题 ID 或凭据写入诊断日志。
     */
    private List<AmbiguityReference> normalizeAmbiguityReferences(EnhancementProviderResponse response) {
        var references = AmbiguityReference.normalize(response.ambiguityReferences(), response.ambiguities());
        int ignored = response.ambiguityReferences().size() - references.size();
        if (ignored > 0) {
            LOGGER.warn("event=model.response.optional_references_ignored requestId={} stage=assembly ignoredCount={}",
                    LogFields.value(MDC.get("requestId")), ignored);
        }
        return references;
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
            throw invalidResponse(Reason.AMBIGUITY_COUNT_INVALID, "ambiguities");
        }
        for (String finding : findings) {
            if (finding == null || finding.isBlank() || finding.length() > 500) {
                throw invalidResponse(Reason.AMBIGUITY_VALUE_INVALID, "ambiguities");
            }
            if (sensitiveValueDetector.containsCredential(finding)) {
                throw invalidResponse(Reason.SENSITIVE_CONTENT, "ambiguities");
            }
        }
        // 模型可能忽略二次检索发现的同名字段冲突；服务端证据优先保留。
        List<String> verifiedConflicts = candidates.stream()
                .filter(value -> value.startsWith("资料对“"))
                .toList();
        for (String finding : verifiedConflicts) {
            if (finding.length() > 500) {
                throw invalidResponse(Reason.AMBIGUITY_VALUE_INVALID, "context.conflicts");
            }
            if (sensitiveValueDetector.containsCredential(finding)) {
                throw invalidResponse(Reason.SENSITIVE_CONTENT, "context.conflicts");
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
                .distinct().toList();
    }

    /** 拒绝重复、空白和疑似含凭据的模型段落，再转换为按类型索引的结果。 */
    private Map<PromptSectionType, PromptSection> collectSections(List<PromptSection> values) {
        if (values == null || values.isEmpty()) {
            throw invalidResponse(Reason.REQUIRED_SECTIONS_MISSING, "sections");
        }
        Map<PromptSectionType, PromptSection> sections = new EnumMap<>(PromptSectionType.class);
        for (PromptSection section : values) {
            if (section == null || section.type() == null || isBlank(section.title()) || isBlank(section.content())) {
                throw invalidResponse(Reason.SECTION_INVALID, "sections");
            }
            if (sensitiveValueDetector.containsCredential(section.title())
                    || sensitiveValueDetector.containsCredential(section.content())) {
                throw invalidResponse(Reason.SENSITIVE_CONTENT, "sections");
            }
            if (sections.putIfAbsent(section.type(), new PromptSection(
                    section.type(), section.title().trim(), section.content().trim())) != null) {
                throw invalidResponse(Reason.SECTION_INVALID, "sections");
            }
        }
        return sections;
    }

    /** 现状、目标与本次选择分别标注来源；待定回答不能冒充已确认事实。 */
    private void appendConfirmedAnswers(
            Map<PromptSectionType, PromptSection> sections,
            ConfirmedDecisionSet decisions
    ) {
        List<ConfirmedPlanDecision> resolved = decisions.knownDecisions().stream()
                .filter(decision -> decision.scope() == Scope.CURRENT_STATE).toList();
        if (resolved.isEmpty()) {
            return;
        }
        PromptSection background = sections.get(PromptSectionType.BACKGROUND);
        String confirmed = resolved.stream()
                .map(decision -> "- " + decision.topic() + "：" + decision.answer())
                .distinct()
                .filter(line -> background.content().lines().noneMatch(existing -> sameCompleteLine(existing, line)))
                .collect(Collectors.joining("\n"));
        if (confirmed.isBlank()) return;
        sections.put(PromptSectionType.BACKGROUND, new PromptSection(
                PromptSectionType.BACKGROUND,
                background.title(),
                background.content() + "\n\n用户已确认的信息（当前情况）：\n" + confirmed
        ));
    }

    /** 执行选择落到任务或输出段落，并核对每条答案确实进入对应段落。 */
    private void appendConfirmedDecisions(Map<PromptSectionType, PromptSection> sections,
                                          ConfirmedDecisionSet decisions) {
        Map<PromptSectionType, List<String>> required = new EnumMap<>(PromptSectionType.class);
        for (ConfirmedPlanDecision decision : decisions.knownDecisions()) {
            if (decision.scope() == Scope.CURRENT_STATE) continue;
            String confirmed = decision.answer();
            if (confirmed.isBlank()) continue;
            PromptSectionType type = decision.topic().startsWith("输出") || decision.topic().startsWith("交付")
                    ? PromptSectionType.OUTPUT : PromptSectionType.TASK;
            required.computeIfAbsent(type, ignored -> new ArrayList<>())
                    .add("- " + decision.topic() + "：" + confirmed);
        }
        for (var entry : required.entrySet()) {
            PromptSectionType type = entry.getKey();
            PromptSection section = sections.get(type);
            // 模型提到候选词也可能是在否定它，不能以出现答案字符串作为已落实的证据。
            List<String> missing = entry.getValue().stream().distinct()
                    .filter(line -> section.content().lines().noneMatch(existing -> sameCompleteLine(existing, line)))
                    .toList();
            if (!missing.isEmpty()) {
                sections.put(type, new PromptSection(type, section.title(), section.content()
                        + "\n\n用户已确认的信息（本次执行选择，须遵守平台约束）：\n" + String.join("\n", missing)));
            }
            if (entry.getValue().stream().anyMatch(line -> sections.get(type).content().lines()
                    .noneMatch(existing -> sameCompleteLine(existing, line)))) {
                throw invalidResponse(Reason.CONFIRMED_DECISION_MISSING, "confirmedDecisions");
            }
        }
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

    /** 完整行仅允许排版空白与句末标点差异；否定、版本、运算符和条件仍逐字相同。 */
    private boolean sameCompleteLine(String existing, String expected) {
        return completeLineKey(existing).equals(completeLineKey(expected));
    }

    /** 英文型号中的分词可能改变取值，不把 `A B` 与 `AB` 归成同一已确认事实。 */
    private String completeLineKey(String line) {
        return java.text.Normalizer.normalize(line.strip(), java.text.Normalizer.Form.NFKC)
                .replaceFirst("^[-*•]\\s*", "- ").replaceAll("\\s+", " ")
                .replaceAll("[。！!]+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ProviderException invalidResponse(Reason reason, String field) {
        return new ProviderResponseValidationException(reason, field);
    }
}
