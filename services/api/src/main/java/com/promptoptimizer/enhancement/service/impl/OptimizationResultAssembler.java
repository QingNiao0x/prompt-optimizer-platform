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
import com.promptoptimizer.provider.domain.JavaCodeDeliveryContract;
import com.promptoptimizer.provider.domain.DraftDeliveryContract;
import com.promptoptimizer.provider.domain.AmbiguityReference;
import com.promptoptimizer.common.logging.LogFields;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.policy.domain.ConstraintBundle;
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
        var decisions = ConfirmedDecisionSet.from(planConfirmed ? planAnswers : List.of());
        var intent = TaskIntentResolver.resolve(template.code(), rawPrompt, decisions.knownDecisions());
        return assemble(providerResponse, context, template, ambiguities, planAnswers, planConfirmed,
                ConstraintBundle.fromLegacy(constraints, intent.engineeringConstraints()), includeExamples,
                latencyMs, rawPrompt, planningFacts, planningWarnings);
    }

    /** 正常编排传递同一份分组规则，模型不能改写固定平台块或覆盖任务与用户权限边界。 */
    public OptimizationResult assemble(
            EnhancementProviderResponse providerResponse, ContextSnapshot context, PromptTemplate template,
            List<String> ambiguities, List<PlanAnswer> planAnswers, boolean planConfirmed,
            ConstraintBundle constraintBundle, boolean includeExamples, long latencyMs, String rawPrompt,
            List<PlanningFactCard> planningFacts, List<String> planningWarnings
    ) {
        var taskView = EffectiveTaskViewFactory.create(rawPrompt, template.code(),
                planConfirmed ? planAnswers : List.of(), planningFacts, includeExamples);
        return assemble(providerResponse, context, template, ambiguities, planAnswers, planConfirmed,
                constraintBundle, includeExamples, latencyMs, rawPrompt, planningFacts, planningWarnings, taskView);
    }

    /** 生产路径使用编排器冻结的同一任务视图；兼容入口仅在此之前构建一次。 */
    public OptimizationResult assemble(
            EnhancementProviderResponse providerResponse, ContextSnapshot context, PromptTemplate template,
            List<String> ambiguities, List<PlanAnswer> planAnswers, boolean planConfirmed,
            ConstraintBundle constraintBundle, boolean includeExamples, long latencyMs, String rawPrompt,
            List<PlanningFactCard> planningFacts, List<String> planningWarnings,
            com.promptoptimizer.enhancement.domain.EffectiveTaskView taskView
    ) {
        return assemble(providerResponse, context, template, ambiguities, planAnswers, planConfirmed,
                constraintBundle, includeExamples, latencyMs, rawPrompt, planningFacts, planningWarnings,
                taskView, null);
    }

    /**
     * 模型已按显式模式生成正文；本层保留主框架次序与展示形式，不用重排代替增强。
     * 任务校验、确认绑定、示例和平台固定条款均共用原有组装路径。
     * 缺省偏好继续使用模型与任务的自动呈现，兼容旧客户端和已保存记录。
     */
    public OptimizationResult assemble(
            EnhancementProviderResponse providerResponse, ContextSnapshot context, PromptTemplate template,
            List<String> ambiguities, List<PlanAnswer> planAnswers, boolean planConfirmed,
            ConstraintBundle constraintBundle, boolean includeExamples, long latencyMs, String rawPrompt,
            List<PlanningFactCard> planningFacts, List<String> planningWarnings,
            com.promptoptimizer.enhancement.domain.EffectiveTaskView taskView,
            com.promptoptimizer.enhancement.domain.PromptFormat requestedFormat
    ) {
        if (providerResponse == null || isBlank(providerResponse.provider()) || isBlank(providerResponse.model())) {
            throw invalidResponse(Reason.METADATA_INVALID, "provider");
        }
        Map<PromptSectionType, PromptSection> sections = collectSections(providerResponse.sections());
        // 本次服务端来源先展开，再经过原有事实、未决、敏感及业务校验；模型引用不绕过保护。
        var sourceProjection = BoundRequirementProjection.expandTracked(sections, taskView, constraintBundle);
        sections.values().forEach(section -> ProfessionalReviewBoundary.validate(section.content(), taskView));
        if (!sections.keySet().containsAll(REQUIRED_TYPES)) {
            throw invalidResponse(Reason.REQUIRED_SECTIONS_MISSING, "sections");
        }
        var requirementBoundaries = taskView.requirementBoundaries();
        var meaning = taskView.meaning();
        sections.forEach((type, section) -> meaning.validate(section.content(), "sections." + type));
        var inferenceContract = com.promptoptimizer.provider.domain.EvidenceInferenceContract.from(taskView, context);
        var presentation = taskView.intent().deliveryProfile() == com.promptoptimizer.template.domain.TaskDeliveryProfile.TRANSLATION
                || providerResponse.assessment() != null && providerResponse.assessment().presentation()
                == com.promptoptimizer.provider.domain.TaskEnhancementAssessment.Presentation.COMPACT
                ? com.promptoptimizer.enhancement.domain.PromptFormat.COMPACT
                : com.promptoptimizer.enhancement.domain.PromptFormat.STRUCTURED;
        sections.forEach((type, section) -> inferenceContract.validate(section.content(), "sections." + type));
        sections.forEach((type, section) -> requirementBoundaries.validate(section.content(), "sections." + type));

        ConfirmedDecisionSet decisions = ConfirmedDecisionSet.from(planConfirmed ? planAnswers : List.of());
        var resolvedState = ResolvedPlanState.from(planConfirmed ? decisions : ConfirmedDecisionSet.from(List.of()), rawPrompt);
        // 先建立有效执行视图，再提取规则；原始提示词与卡片仍完整保存，旧未知不能被保真流程补回。
        String resolvedRawPrompt = taskView.effectivePrompt();
        sections.replaceAll((type, section) -> new PromptSection(type, section.title(), resolvedState.reconcile(section.content())));
        String evidenceQuery = decisions.retrievalQuery(rawPrompt);
        List<PlanningFactCard> eligibleFacts = new PlanningFactCardExtractor()
                .filterBoundFacts(planningFacts, context, evidenceQuery);
        List<String> documentFacts = contextFactPreserver.facts(context, evidenceQuery);
        // 假设来源与实际确认分开核对；提醒也可能带有事实断言，不能只校验四要素正文。
        List<String> conditionalEvidence = new ArrayList<>(documentFacts);
        conditionalEvidence.add(rawPrompt == null ? "" : rawPrompt);
        eligibleFacts.stream().map(PlanningFactCard::evidence).forEach(conditionalEvidence::add);
        List<ConfirmedPlanDecision> actualConfirmations = planConfirmed ? decisions.knownDecisions() : List.of();
        var conditionalGuard = ConditionalConfirmationGuard.prepare(rawPrompt, conditionalEvidence, actualConfirmations);
        sections.forEach((type, section) -> conditionalGuard.validate(section.content(), "sections." + type));
        var unresolvedContract = UnresolvedDecisionContract.from(resolvedRawPrompt, decisions, conditionalEvidence);
        String effectiveRawPrompt = unresolvedContract.reconcileCurrentText(resolvedRawPrompt);
        var draftDelivery = DraftDeliveryContract.from(effectiveRawPrompt, template, actualConfirmations);
        sections.replaceAll((type, section) -> new PromptSection(type, section.title(),
                unresolvedContract.reconcileCurrentText(section.content())));
        sections.forEach((type, section) -> unresolvedContract.validate(section.content(), "sections." + type));
        sections.forEach((type, section) -> validateUserFacingProtocol(section.content(), rawPrompt, "sections." + type));
        // 在追加平台交付条款前校验模型的主目标；正确尾注不能掩盖前文把正文改成方案的矛盾。
        for (PromptSectionType type : List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT)) {
            // 辅助槽可为空，表示交付已在 TASK 完整说明；非空 OUTPUT 仍不能与主任务冲突。
            String content = sections.get(type).content();
            if (!content.isBlank()) draftDelivery.validateDelivery(content, "sections." + type);
        }
        if (providerResponse.ambiguities() != null) {
            for (int index = 0; index < providerResponse.ambiguities().size(); index++) {
                conditionalGuard.validate(providerResponse.ambiguities().get(index), "ambiguities[" + index + "]");
            }
        }
        List<String> explicitRules = fidelityGuard.explicitRules(effectiveRawPrompt, decisions.decisions());
        var sourceObjects = SourceObjectContract.from(effectiveRawPrompt, context, eligibleFacts,
                planConfirmed ? decisions.knownDecisions() : List.of());
        sections.forEach((type, section) -> sourceObjects.validate(section.content(), "sections." + type));
        var identifierRelations = NamedIdentifierContract.from(effectiveRawPrompt, context, eligibleFacts,
                planConfirmed ? decisions.decisions() : List.of());
        sections.forEach((type, section) -> identifierRelations.validate(section.content(), "sections." + type));
        if (providerResponse.ambiguities() != null) providerResponse.ambiguities().forEach(
                finding -> identifierRelations.validate(finding, "ambiguities"));
        validateExecutionRules(sections, explicitRules, eligibleFacts, documentFacts);
        boolean minimal = MinimalPromptOrganizer.organize(sections, effectiveRawPrompt,
                planConfirmed ? decisions : ConfirmedDecisionSet.from(List.of()), providerResponse.assessment());
        PlanDecisionIntegrator.integrate(sections, taskView, sourceProjection.delivered());
        PlanDecisionIntegrator.reconcileDelegatedStatus(sections, taskView);
        // 原件已通过安全与任务校验；仅精简可证明覆盖的资料状态并澄清条件表格，不改原件与来源。
        MaterialStatusCompactor.compact(sections);
        ConditionalTableProjection.reconcile(sections);
        java.util.function.UnaryOperator<String> currentEvidence = value ->
                unresolvedContract.reconcileCurrentText(resolvedState.reconcile(value));
        List<String> currentDocumentFacts = documentFacts.stream().map(value -> {
            String current = currentEvidence.apply(value);
            return value.equals(current) ? current : "按本次明确决定更新（非原文摘录）：" + current;
        }).toList();
        Set<String> updatedFactIds = eligibleFacts.stream()
                .filter(card -> !card.evidence().equals(currentEvidence.apply(card.evidence())))
                .map(PlanningFactCard::id).collect(Collectors.toSet());
        List<PlanningFactCard> currentFacts = eligibleFacts.stream().map(card -> new PlanningFactCard(
                card.id(), card.category(), card.origin(), card.sourcePath(),
                currentEvidence.apply(card.evidence()))).toList();
        if (eligibleFacts.isEmpty()) {
            appendDocumentFacts(sections, currentDocumentFacts);
        } else {
            appendPlanningFacts(sections, currentFacts, currentDocumentFacts, planConfirmed, updatedFactIds);
        }
        appendConstraints(sections, constraintBundle, rawPrompt, sourceProjection);
        String roleExecution = DraftDeliveryContract.roleExecutionGuidance(effectiveRawPrompt, template);
        if (!roleExecution.isBlank()) {
            PromptSection output = sections.get(PromptSectionType.OUTPUT);
            // 启动条件放在格式要求之前；它与最终复制正文一致，不仅停留在优化模型的 system 指导中。
            if (!output.content().contains(roleExecution)) sections.put(PromptSectionType.OUTPUT,
                    new PromptSection(output.type(), output.title(), roleExecution + "\n\n" + output.content()));
        }
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
        if (lengthGuidance.isBlank()) lengthGuidance = TaskBodyLengthContract.guidance(effectiveRawPrompt);
        if (!lengthGuidance.isBlank()) {
            PromptSection output = sections.get(PromptSectionType.OUTPUT);
            sections.put(PromptSectionType.OUTPUT, new PromptSection(output.type(), output.title(),
                    output.content() + "\n\n" + lengthGuidance));
        }
        String codeDelivery = JavaCodeDeliveryContract.guidance(effectiveRawPrompt);
        if (!codeDelivery.isBlank()) {
            PromptSection output = sections.get(PromptSectionType.OUTPUT);
            // 同一完整义务可能已落入任务/输出/约束；只补缺项，原契约和来源不随投影精简而丢失。
            List<String> missingDelivery = AdditionalRuleProjection.uncoveredJavaDelivery(sections, codeDelivery);
            if (!missingDelivery.isEmpty()) sections.put(PromptSectionType.OUTPUT, new PromptSection(output.type(), output.title(),
                    output.content() + "\n\nJava21交付：\n- " + String.join("\n- ", missingDelivery)));
        }
        if (draftDelivery.active()) {
            PromptSection output = sections.get(PromptSectionType.OUTPUT);
            // 起草推进方式随复制正文保留；未知仍由原有参数校验器处理，不转换为默认值。
            sections.put(PromptSectionType.OUTPUT, new PromptSection(output.type(), output.title(),
                    output.content() + "\n\n" + draftDelivery.guidance()));
        }
        List<String> assessed = new PlanQuestionFilter().unresolvedFindings(
                resolveAmbiguities(providerResponse, sections, ambiguities, effectiveRawPrompt, context, eligibleFacts, taskView),
                effectiveRawPrompt, context, eligibleFacts, taskView).stream()
                .filter(finding -> !TaskQuestionScope.unrelatedEngineeringReminder(finding, rawPrompt))
                .filter(finding -> !readOnlyComparison.directedReminder(finding))
                .filter(finding -> !identifierRelations.coveredPendingStatement(finding)).toList();
        if (!planConfirmed) {
            // 用户在原文明确列出的未决问题不能被模型返回的空数组抹掉；绑定 Plan 的旧标签不在此重新引入。
            List<String> providerFindings = assessed;
            assessed = java.util.stream.Stream.concat(assessed.stream(),
                    java.util.stream.Stream.concat(declaredPendingQuestions(rawPrompt).stream(),
                            UnresolvedDecisionContract.declaredPending(rawPrompt).stream())
                            .filter(question -> providerFindings.stream().noneMatch(finding -> coversDeclaredQuestion(finding, question, rawPrompt))))
                    .distinct().toList();
        }
        // 显式独立的机构参数采用当前具名状态，不再同时追加整句公共未知；新条件及新冲突不消除。
        assessed = unresolvedContract.independentlyScopedPending(assessed);
        // 资料里的明确未知也随同一个权威清单交付；旧状态已按完整参数确认更新，不补造缺失事实。
        List<String> currentFindings = assessed;
        assessed = java.util.stream.Stream.concat(assessed.stream(), unresolvedContract.pendingStatements().stream()
                .filter(statement -> !unresolvedContract.coveredByBoundPending(statement, decisions))
                .filter(statement -> java.util.stream.Stream.concat(currentFindings.stream(),
                        decisions.pendingDecisions().stream().map(ConfirmedPlanDecision::answer))
                        .noneMatch(finding -> unresolvedContract.coversPendingStatement(finding, statement))))
                .distinct().toList();
        // 名称与代码是独立关系，不让参数确认或模型空数组掩盖缺少对应依据。
        List<String> relationFindings = assessed;
        assessed = java.util.stream.Stream.concat(assessed.stream(), identifierRelations.pendingStatements().stream()
                .filter(statement -> !identifierRelations.coveredByBoundPending(statement, decisions))
                .filter(statement -> relationFindings.stream().noneMatch(finding -> identifierRelations.coversPendingStatement(finding, statement))))
                .distinct().toList();
        assessed = assessed.stream().filter(finding -> !decisions.resolvesChoiceRequest(finding)).toList();
        assessed = classifyFindings(sections, assessed, taskView.effectivePrompt(), decisions, eligibleFacts, documentFacts, context);
        List<AmbiguityReference> references = normalizeAmbiguityReferences(providerResponse);
        // 先登记新冲突和绑定的未决问题，再归并模型提醒，避免重复项挤占展示预算。
        // 直接增强也可合并相同来源和取值的冲突；仅服务端绑定的 Plan 答案可消除旧问题。
        // 共同拒绝确认已作为独立约束落实时，只省去逐题解释；未决问题的身份和条件仍全部登记。
        var coveredExplanations = taskView.executionRequirements().stream()
                .filter(requirement -> requirement.destination() == PromptSectionType.CONSTRAINTS)
                .filter(requirement -> PlanAnswerSemantics.independentStatements(requirement.value()).stream()
                        .anyMatch(statement -> statement.kind() == PlanAnswerSemantics.StatementKind.NON_CONFIRMATION))
                // 只认完整规则的同向表述，不能因“不能不确认”包含“不确认”就省去用户说明。
                .filter(requirement -> PlanDecisionIntegrator.coveredRule(sections, requirement.statement()))
                .map(requirement -> requirement.statement()).collect(java.util.stream.Collectors.toSet());
        var merged = new PlanAmbiguityMerger(planConfirmed ? decisions : ConfirmedDecisionSet.from(List.of()), rawPrompt,
                coveredExplanations, taskView.executionRequirements().stream()
                        .filter(requirement -> requirement.subject().equals("委托要求"))
                        .map(com.promptoptimizer.enhancement.domain.EffectiveTaskView.ExecutionRequirement::questionId)
                        .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()))
                .merge(assessed, ambiguities, references);
        List<String> remainingAmbiguities = merged.messages();
        merged.executionPrerequisites().forEach(value -> validateUserFacingProtocol(value, rawPrompt, "ambiguities"));
        List<String> resultWarnings = new ArrayList<>(collectWarnings(context, planningWarnings));
        resultWarnings.addAll(ProfessionalReviewBoundary.warnings(taskView));
        if (merged.omittedCount() > 0) {
            resultWarnings.add("待确认事项已去重，本次展示前 8 项，另有 " + merged.omittedCount()
                    + " 项未展示；所有未决条件已完整保留在约束部分，仅限制依赖它们的内容。");
        }
        removeRepeatedProviderPrerequisites(sections, merged.executionPrerequisites(), decisions);
        ProviderPrerequisiteCompactor.compact(sections, merged.executionPrerequisites(), decisions, rawPrompt);
        unresolvedContract.compactPendingStatements(sections);
        // 普通起草不追加面向所有指标的通用长契约；具名参数、观察边界与原定交付保护仍保留。
        String pendingDeliveryGuidance = draftDelivery.active() && !unresolvedContract.hasNamedParameters()
                ? unresolvedContract.scopedDeliveryGuidance() : unresolvedContract.baseDeliveryGuidance();
        // 已委托的设计在任务里要求交付，参数仍未确认；先补完整执行表达，再判断是否需要逐题追加。
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, merged.executionPrerequisites(), taskView);
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, taskView, providerResponse.assessment());
        // 只影响复制正文的追加；权威未决清单和题答来源继续完整保存，不能借去重擅自确认参数。
        List<String> deliveredPrerequisites = PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(
                sections, merged.executionPrerequisites(), taskView).stream()
                .filter(value -> !sourceProjection.coveredPending(sections).contains(value)).toList();
        appendExecutionPrerequisites(sections, deliveredPrerequisites, pendingDeliveryGuidance,
                draftDelivery.prerequisiteHeading(), taskView);
        if (unresolvedContract.hasNamedParameters()) {
            PromptSection output = sections.get(PromptSectionType.OUTPUT);
            String parameterEvidence = unresolvedContract.independentEvidenceGuidance();
            // 参数状态与原选值随复制正文交付，不依赖页面问答；资料依据仍不提升为用户确认。
            if (!parameterEvidence.isBlank() && !output.content().contains(parameterEvidence)) {
                sections.put(PromptSectionType.OUTPUT, new PromptSection(output.type(), output.title(),
                        output.content() + "\n\n" + parameterEvidence));
            }
        }
        if (!identifierRelations.guidance().isBlank()) {
            PromptSection output = sections.get(PromptSectionType.OUTPUT);
            if (!output.content().contains(identifierRelations.guidance())) {
                sections.put(PromptSectionType.OUTPUT, new PromptSection(output.type(), output.title(),
                        output.content() + "\n\n" + identifierRelations.guidance()));
            }
        }
        // 未决清单也是正文中的权威规则来源；先落入正文再查缺补齐，避免同一禁止规则被追加两遍。
        var deliveredOutputs = ExplicitOutputRequirements.complete(sections, taskView.effectivePrompt(), sourceProjection.coveredRules(sections));
        Set<String> coveredSourceRules = new java.util.HashSet<>(sourceProjection.coveredRules(sections));
        coveredSourceRules.addAll(ExplicitOutputRequirements.rulesOf(deliveredOutputs));
        appendExplicitRules(sections, ProviderPrerequisiteCompactor.uncoveredRules(explicitRules.stream()
                        .filter(rule -> !coveredSourceRules.contains(rule)).toList(),
                merged.executionPrerequisites(), decisions, rawPrompt).stream()
                .flatMap(rule -> PlanDecisionIntegrator.uncoveredRuleClauses(sections, rule).stream()).toList());
        // 补回规则也可能重述同一未知；权威执行前提所在的标题范围跳过，只检查其余完整断言。
        ProviderPrerequisiteCompactor.compact(sections, merged.executionPrerequisites(), decisions, rawPrompt);
        // 一个权威列表同时驱动 API 与段落，避免 UI 与模型返回的旧 CLARIFICATIONS 互相矛盾。
        sections.remove(PromptSectionType.CLARIFICATIONS);
        if (!remainingAmbiguities.isEmpty()) {
            sections.put(PromptSectionType.CLARIFICATIONS, new PromptSection(
                    PromptSectionType.CLARIFICATIONS, "待确认事项",
                    remainingAmbiguities.stream().map(value -> "- " + value).collect(Collectors.joining("\n"))
            ));
        }
        // 显式结构化只保留模型或原文实际给出的验收，不为Mock和旧响应补通用模块；旧模式兼容不变。
        if (requestedFormat != com.promptoptimizer.enhancement.domain.PromptFormat.STRUCTURED
                && providerResponse.assessment() == null && presentation == com.promptoptimizer.enhancement.domain.PromptFormat.STRUCTURED) {
            sections.computeIfAbsent(PromptSectionType.ACCEPTANCE,
                    ignored -> new PromptSection(PromptSectionType.ACCEPTANCE, "验收标准", template.acceptanceGuidance()));
        }
        ExampleResultIntegrator.integrate(sections, taskView.examples());
        String boundaryGuidance = requirementBoundaries.missingGuidance(sections.values().stream()
                .map(PromptSection::content).collect(Collectors.joining("\n")));
        if (!boundaryGuidance.isBlank()) {
            PromptSection boundarySection = sections.get(PromptSectionType.CONSTRAINTS);
            // 具体边界随复制正文保留；不增加待确认问卷，也不改换原定交付物。
            String missing = boundaryGuidance.lines().filter(rule -> !PlanDecisionIntegrator.coveredRule(sections, rule))
                    .collect(Collectors.joining("\n"));
            if (!missing.isBlank()) sections.put(PromptSectionType.CONSTRAINTS,
                    new PromptSection(boundarySection.type(), boundarySection.title(), boundarySection.content() + "\n" + missing));
        }

        if (!inferenceContract.guidance().isBlank()) {
            appendConstraintBlock(sections, "推断依据", List.of(inferenceContract.guidance()));
        }
        // 平台交付契约有唯一正文责任段；仅合并完整等价句，表格、代码和独立业务章节继续保护。
        var deliveryAuthority = new java.util.LinkedHashMap<PromptSectionType, String>();
        if (!inferenceContract.guidance().isBlank()) {
            deliveryAuthority.put(PromptSectionType.CONSTRAINTS, inferenceContract.guidance());
        }
        if (!deliveredPrerequisites.isEmpty() && !pendingDeliveryGuidance.isBlank()) {
            deliveryAuthority.merge(PromptSectionType.CONSTRAINTS, pendingDeliveryGuidance, (existing, added) -> existing + "\n" + added);
        }
        if (unresolvedContract.hasNamedParameters()) {
            deliveryAuthority.put(PromptSectionType.OUTPUT, unresolvedContract.independentEvidenceGuidance());
        }
        if (!identifierRelations.guidance().isBlank()) {
            deliveryAuthority.merge(PromptSectionType.OUTPUT, identifierRelations.guidance(), (existing, added) -> existing + "\n" + added);
        }
        if (!codeDelivery.isBlank()) {
            deliveryAuthority.merge(PromptSectionType.OUTPUT, codeDelivery, (existing, added) -> existing + "\n" + added);
        }
        if (draftDelivery.active()) {
            deliveryAuthority.merge(PromptSectionType.OUTPUT, draftDelivery.guidance(), (existing, added) -> existing + "\n" + added);
        }
        // 显式结构化采用可空辅助槽；旧COMPACT和自动模式不改变其既有组装行为。
        boolean structured = requestedFormat == com.promptoptimizer.enhancement.domain.PromptFormat.STRUCTURED;
        AuthoritativeDeliveryCompactor.compact(sections, deliveryAuthority, structured);
        ExecutionRuleCompactor.compact(sections, deliveredOutputs.stream()
                .map(ExplicitOutputRequirements::bodyOf).collect(Collectors.toUnmodifiableSet()), structured);
        // 显式输出块是用户交付契约；模型遗漏时从同一来源补回，不依赖评估声明或改写后的字段相似度。
        Set<String> finalOutputSources = new java.util.HashSet<>(sourceProjection.coveredRules(sections));
        finalOutputSources.addAll(deliveredOutputs);
        ExplicitOutputRequirements.complete(sections, taskView.effectivePrompt(), finalOutputSources);
        PromptSection constraintSection = sections.get(PromptSectionType.CONSTRAINTS);
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(constraintSection.type(), constraintSection.title(),
                PlatformConstraintRenderer.appendMandatory(constraintSection.content(), constraintBundle)));
        if (structured) presentCompletedConfirmations(sections, taskView);
        // 平台补回后的视图仍须守住确认范围；不能只验证模型原文而漏掉自动追加的规则。
        sections.forEach((type, section) -> unresolvedContract.validate(section.content(), "sections." + type));
        sections.forEach((type, section) -> conditionalGuard.validate(section.content(), "sections." + type));
        sections.forEach((type, section) -> requirementBoundaries.validate(section.content(), "sections." + type));
        // 自然模式按同一有效任务选中的主框架展开，绝不据框架补造段落或省去平台保护。
        var sectionOrder = requestedFormat == com.promptoptimizer.enhancement.domain.PromptFormat.COMPACT
                ? com.promptoptimizer.template.domain.PromptFrameworkSelector.select(taskView).sectionOrder()
                : List.of(PromptSectionType.values());
        List<PromptSection> ordered = sectionOrder.stream()
                .map(sections::get)
                // 内部四槽直到规则补全和安全复查后才去空；正文、复制与历史共用同一投影。
                .filter(section -> section != null && !section.content().isBlank())
                .toList();
        // 显式开关覆盖模型及翻译的展示偏好，但不改变验收要求等已完成的语义组装。
        var resultFormat = requestedFormat == null ? presentation : requestedFormat;
        var result = new OptimizationResult(
                renderPrompt(ordered, resultFormat),
                ordered,
                context,
                remainingAmbiguities,
                constraintBundle.visibleConstraints(),
                template.code(),
                new ProviderMetadata(
                        providerResponse.provider(),
                        providerResponse.model(),
                        providerResponse.mock()
                ),
                Math.max(0, latencyMs),
                resultWarnings,
                evidenceCards(eligibleFacts, documentFacts),
                resultFormat,
                constraintBundle
        );
        // 统一校验最终结果的全部文本投影，避免段落安全但警告、来源或追加内容仍回显凭据。
        // 此投影只在本次调用中检查，禁止写入日志或额外持久化。
        if (sensitiveValueDetector.containsCredential(result.toString())) {
            throw invalidResponse(Reason.SENSITIVE_CONTENT, "result");
        }
        return result;
    }

    /** 用户要求讲解某协议时保留合法代码名，其他任务不能把模型输入的内部字段当作读者操作指令。 */
    private void validateUserFacingProtocol(String text, String rawPrompt, String field) {
        if (text == null) return;
        var names = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:taskView|executionRequirements|pendingDecisions|confirmedDecisions|planAnswers|questionId|sourceQuestionIds|designDelegated)(?![A-Za-z0-9_])")
                .matcher(text);
        while (names.find()) {
            if (rawPrompt == null || !rawPrompt.contains(names.group())) throw invalidResponse(Reason.RULE_CONFLICT, field);
        }
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
        if (end < 2) return coversExplicitPendingDeclaration(finding, question);
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

    /** 直接增强只避免补回已被完整说明覆盖的短未知；主体、年份和属性必须逐字一致。 */
    private boolean coversExplicitPendingDeclaration(String finding, String declaration) {
        String expected = pendingDeclarationKey(declaration).replaceAll("[。]+$", "");
        if (!expected.matches("[^，,。；;：:？?\\\"“”`<>≤≥]{2,160}未决$")
                || expected.matches(".*(?:若|如果|假如|假设|例如|引用).*")) return false;
        String observed = pendingDeclarationKey(finding);
        if (!observed.startsWith(expected)) return false;
        return observed.length() == expected.length() || observed.substring(expected.length())
                .matches("^[，,。；;].*");
    }

    /** 只归一化未决谓词及排版，不删除适用范围、比较符或否定。 */
    private String pendingDeclarationKey(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).replaceAll("\\s+", "")
                .replaceAll("(?:尚未|仍未|暂未|未)(?:确定|决定|核实|明确)", "未决");
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
        List<String> missing = rules.stream().flatMap(rule -> AdditionalRuleProjection.uncovered(sections, rule).stream())
                .distinct().toList();
        // missing已排除条件/引用等非当前证据，不能再用裸全文相等或子串把缺项过滤掉。
        appendMissingConstraintBlock(sections, "用户明确规则（须遵守平台权限边界）", missing);
    }

    /** 未决与冲突必须随可复制正文交付；不把暂不确定转换成模型自行选择的许可。 */
    private void appendExecutionPrerequisites(Map<PromptSectionType, PromptSection> sections, List<String> prerequisites,
                                              String deliveryGuidance, String heading,
                                              com.promptoptimizer.enhancement.domain.EffectiveTaskView taskView) {
        var common = PlanDecisionIntegrator.commonDelegatedQuestions(prerequisites, taskView);
        appendConstraintBlock(sections, common.isPresent()
                ? heading + "（已委托提出拟议设计，实际采用参数仍未确认）" : heading,
                common.orElse(prerequisites));
        if (!prerequisites.isEmpty() && !deliveryGuidance.isBlank()) {
            appendConstraintBlock(sections, "未决决定的交付边界", List.of(deliveryGuidance));
        }
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

    /**
     * 只标明服务端已完整绑定的确认状态，不把题干标签重新呈现成当前择一请求。
     * 整体未决、部分回答及未来条件不标为完成；原始题答、权限及独立规则均保留。
     */
    private void presentCompletedConfirmations(Map<PromptSectionType, PromptSection> sections,
            com.promptoptimizer.enhancement.domain.EffectiveTaskView task) {
        Set<String> completedIds = task.confirmedDecisions().stream()
                .filter(decision -> decision.scope() != Scope.UNRESOLVED)
                .map(ConfirmedPlanDecision::questionId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        for (var requirement : task.executionRequirements()) {
            if (!completedIds.contains(requirement.questionId())) continue;
            PromptSection section = sections.get(requirement.destination());
            if (section == null) continue;
            boolean fenced = false;
            List<String> lines = new ArrayList<>();
            for (String line : section.content().lines().toList()) {
                String stripped = line.strip();
                if (stripped.startsWith("```") || stripped.startsWith("~~~")) fenced = !fenced;
                // 只有本次完整执行原句的独立行参与呈现，不从引文、代码或子串猜测语义相等。
                String statement = stripped.replaceFirst("^-\\s+", "");
                boolean boundLine = !fenced && !line.startsWith("    ") && !line.startsWith("\t")
                        && statement.equals(requirement.statement());
                lines.add(boundLine ? line.replace(requirement.statement(), "本次已完成确认：" + requirement.statement()) : line);
            }
            sections.put(section.type(), new PromptSection(section.type(), section.title(), String.join("\n", lines)));
        }
    }

    /** 修改同一份结构化约束，保证正文、编辑、复制、历史与再次增强使用一致的内容。 */
    private void appendConstraintBlock(Map<PromptSectionType, PromptSection> sections, String title, List<String> values) {
        if (values.isEmpty()) return;
        PromptSection section = sections.get(PromptSectionType.CONSTRAINTS);
        var missing = values.stream().distinct().filter(value -> !section.content().contains(value)).toList();
        appendMissingConstraintBlock(sections, title, missing);
    }

    /** 只写入调用方已判定未覆盖的规则，不再次用子串判断抹去条件或否定差异。 */
    private void appendMissingConstraintBlock(Map<PromptSectionType, PromptSection> sections, String title, List<String> missing) {
        if (missing.isEmpty()) return;
        PromptSection section = sections.get(PromptSectionType.CONSTRAINTS);
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
                                     List<String> documentFacts, boolean planConfirmed, Set<String> updatedFactIds) {
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
                // 当前选值可能来自回答；更新后的正文不得冒充文件原文，原始卡片仍独立返回。
                .map(card -> "- " + card.sourcePath()
                        + (updatedFactIds.contains(card.id()) ? "（按本次明确决定更新，非原文摘录）" : "")
                        + "：" + card.evidence())
                .collect(Collectors.joining("\n"));
        if (!sourcedFacts.isBlank()) {
            background = new PromptSection(
                    PromptSectionType.BACKGROUND,
                    background.title(),
                    background.content() + (planConfirmed ? "\n\nPlan 阶段绑定的资料事实与二次检索补充" : "\n\n资料的当前执行视图")
                            + "（原始摘录见资料依据；现状与目标分别核对，不代表已经实现）：\n"
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
                                            List<String> candidates, String effectiveRawPrompt,
                                            ContextSnapshot context, List<PlanningFactCard> safeFacts,
                                            com.promptoptimizer.enhancement.domain.EffectiveTaskView taskView) {
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
        // 安全与数量检查先完成，再复用既有的精确泛化提醒过滤；不扩大词表或删除真实用户选择。
        // 否则原本不会展示的固定兜底句会提前触发 assessment 冲突，造成可确定的误拒绝。
        List<String> modelFindings = findings.stream().map(String::trim)
                .filter(value -> !GENERIC_WARNINGS.contains(value.replaceAll("[。.!！]+$", "")))
                .distinct().toList();
        if (response.assessment() != null && response.ambiguities() != null) {
            // 已有事实能确定解决的问题不应先触发分类冲突；只复用已知事实判断，委托设计不据此静默删题。
            // 服务端冲突尚未合入，不能被模型 assessment 反向否定；敏感值及数量校验仍在筛选之前。
            List<String> unresolvedModelFindings = new PlanQuestionFilter().unresolvedKnownFindings(
                    modelFindings, effectiveRawPrompt, context, safeFacts, taskView);
            response.assessment().validateModelDecisions(unresolvedModelFindings);
        }
        // 只校验模型自己声明的缺口，不能用其 assessment 删除服务端发现的冲突和原始未决条件。
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
                        java.util.stream.Stream.concat(serverFindings.stream(), modelFindings.stream()))
                .map(String::trim)
                .filter(value -> !GENERIC_WARNINGS.contains(value.replaceAll("[。.!！]+$", "")))
                .distinct().toList();
    }

    /** 保留四要素槽位；辅助槽可空，空任务、缺内容、重复类型及疑似凭据仍拒绝。 */
    private Map<PromptSectionType, PromptSection> collectSections(List<PromptSection> values) {
        if (values == null || values.isEmpty()) {
            throw invalidResponse(Reason.REQUIRED_SECTIONS_MISSING, "sections");
        }
        Map<PromptSectionType, PromptSection> sections = new EnumMap<>(PromptSectionType.class);
        for (PromptSection section : values) {
            if (section == null || section.type() == null || isBlank(section.title()) || section.content() == null
                    || (section.content().isBlank() && !section.type().allowsEmptyContent())) {
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

    /** 先落入任务与用户权限分组；固定平台块在所有普通压缩完成后追加。 */
    private void appendConstraints(
            Map<PromptSectionType, PromptSection> sections,
            ConstraintBundle constraints,
            String rawPrompt, BoundRequirementProjection.Expansion sourceProjection
    ) {
        PromptSection current = sections.get(PromptSectionType.CONSTRAINTS);
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(
                current.type(), current.title(), PlatformConstraintRenderer.providerText(current.content(), rawPrompt, constraints)));
        // 固定平台规则协调可能移除旧呈现，必须在协调后核对来源仍然存在，不能沿用协调前的覆盖集合。
        var coveredSourceRules = sourceProjection.coveredRules(sections);
        // 仅任务规则采用完整断言覆盖；权限、来源、未决状态及固定平台块保留各自原有保护。
        appendMissingConstraintBlock(sections, "任务相关约束", TaskConstraintCoverage.uncovered(
                sections, constraints.taskSpecific().stream().filter(rule -> !coveredSourceRules.contains(rule)).toList()));
        appendConstraintBlock(sections, "用户补充权限边界", constraints.additionalPermissions());
    }

    /** 输出已由生成策略组织；呈现只决定外层标题，不裁剪代码、引用、条件或来源。 */
    private String renderPrompt(List<PromptSection> sections, com.promptoptimizer.enhancement.domain.PromptFormat format) {
        return sections.stream()
                .filter(section -> section.type() != PromptSectionType.CLARIFICATIONS)
                .map(section -> format == com.promptoptimizer.enhancement.domain.PromptFormat.COMPACT
                        ? section.content() : "## " + section.title() + "\n" + section.content())
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
