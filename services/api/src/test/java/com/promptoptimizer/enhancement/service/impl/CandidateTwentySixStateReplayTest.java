package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.history.service.impl.MybatisOptimizationHistoryService;
import com.promptoptimizer.history.service.impl.OptimizationHistoryPersistenceService;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回放二十五保留的三个状态失败，检查真实分类、模型输入和组装／历史边界，不调用外部模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateTwentySixStateReplayTest {
    private static final String QUESTION = "4 类任务具体指哪四类？";
    private static final String RAW = "撰写中文研究设计的方法部分，说明准确性、完整性、清晰度与可执行性评价以及分歧处理。\n" + QUESTION;
    private static final List<String> FAILURES = List.of("如果以后批准，再提出拟议设计。", "只委托评分设计，分类由我决定。", "评分量尺以后确认。");
    private static final ContextSnapshot EMPTY = new ContextSnapshot("", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "EMPTY");

    @ParameterizedTest
    @ValueSource(strings = {"如果以后批准，再提出拟议设计。", "只委托评分设计，分类由我决定。", "评分量尺以后确认。"})
    void preservedFailuresKeepTheBoundQuestionPendingWithoutInventingAChoice(String answer) {
        var answers = answers(answer);
        var decisions = ConfirmedDecisionSet.from(answers);
        assertThat(decisions.decisions()).singleElement().satisfies(value -> {
            assertThat(value.scope()).isEqualTo(ConfirmedPlanDecision.Scope.UNRESOLVED);
            assertThat(value.answer()).isEqualTo(answer);
            assertThat(value.question()).isEqualTo(QUESTION);
        });
        assertThat(decisions.knownDecisions()).isEmpty();
        assertThat(decisions.retrievalQuery("原始任务")).isEqualTo("原始任务");
        assertThat(decisions.resolvesChoiceRequest(QUESTION)).isFalse();
        var task = task(answer);
        assertThat(task.effectivePrompt()).isEqualTo(RAW);
        assertThat(task.pendingDecisions()).anySatisfy(value -> {
            assertThat(value.questionId()).isEqualTo("q");
            assertThat(value.question()).isEqualTo(QUESTION);
        });
        assertThat(task.modelInput().pendingDecisions()).anySatisfy(value -> {
            assertThat(value.questionId()).isEqualTo("q");
            assertThat(value.question()).isEqualTo(QUESTION);
            assertThat(value.scope()).isEqualTo(ConfirmedPlanDecision.Scope.UNRESOLVED);
        });
        var merged = new PlanAmbiguityMerger(decisions).merge(List.of(QUESTION), List.of(), List.of());
        assertThat(merged.executionPrerequisites()).anyMatch(value -> value.contains(QUESTION));
    }

    @Test
    void partialDelegationIsAnExecutionInstructionAndNeverACategoryValue() {
        var task = task(FAILURES.get(1));
        assertThat(task.executionRequirements()).anySatisfy(value -> {
            assertThat(value.subject()).isEqualTo("局部委托要求");
            assertThat(value.statement()).isEqualTo("只委托评分设计");
            assertThat(value.destination()).isEqualTo(PromptSectionType.TASK);
            assertThat(value.question()).isEqualTo(QUESTION);
        });
        assertThat(task.executionRequirements()).anySatisfy(value -> {
            assertThat(value.subject()).isEqualTo("未确认边界");
            assertThat(value.statement()).isEqualTo("分类由我决定。");
            assertThat(value.destination()).isEqualTo(PromptSectionType.CONSTRAINTS);
        });
        assertThat(task.modelInput().executionRequirements()).allSatisfy(value ->
                assertThat(value.sourceQuestionIds()).containsExactly("q"));
        assertThat(task.modelInput().pendingDecisions()).allSatisfy(value ->
                assertThat(value.designDelegated()).as("评分委托不能授予分类设计权限").isFalse());
        assertThat(assemble(FAILURES.get(1)).ambiguities()).noneMatch(value ->
                value.contains("已委托提出拟议设计") && value.contains(QUESTION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果以后批准，再提出拟议设计。", "只委托评分设计，分类由我决定。", "评分量尺以后确认。"})
    void copyAndScopedHistoryPreservePendingStateAndVerbatimSources(String answer) throws Exception {
        var result = assemble(answer);
        assertThat(result.ambiguities()).anyMatch(value -> value.contains(QUESTION));
        assertThat(result.optimizedPrompt()).contains(QUESTION).doesNotContain("已确认分类", "分类已选定");
        var json = com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        // 只构造现有请求协议；不创建用户、Plan会话或真实历史。
        var request = json.readValue("""
                {"rawPrompt":"撰写研究方法部分。","context":{},
                 "planConfirmation":{"planId":"00000000-0000-0000-0000-000000000026","answers":[]}}
                """, OptimizationRequest.class);
        var requestNode = json.valueToTree(request);
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("planConfirmation"))
                .set("answers", json.valueToTree(answers(answer)));
        request = json.treeToValue(requestNode, OptimizationRequest.class);
        var actor = TestActors.currentActor();
        var identity = actor.require();
        var mapper = mock(OptimizationRecordMapper.class);
        var persistence = mock(OptimizationHistoryPersistenceService.class);
        var saved = new java.util.concurrent.atomic.AtomicReference<OptimizationRecordEntity>();
        when(persistence.persist(any(), eq(identity))).thenAnswer(invocation -> {
            OptimizationRecordEntity entity = invocation.getArgument(0);
            entity.setCreatedAt(java.time.OffsetDateTime.parse("2026-10-17T00:00:00Z"));
            saved.set(entity);
            return entity.getId();
        });
        when(mapper.selectByIdAndScope(any(), eq(identity.tenantId()), eq(identity.workspaceId())))
                .thenAnswer(invocation -> saved.get());
        var history = new MybatisOptimizationHistoryService(mapper, persistence,
                mock(com.promptoptimizer.enhancement.service.EnhancementOrchestrator.class), actor, json);
        var restored = history.get(history.save(request, result));
        assertThat(restored.optimizedPrompt()).isEqualTo(result.optimizedPrompt());
        assertThat(restored.sections()).isEqualTo(result.sections());
        assertThat(restored.appliedConstraints()).isEqualTo(result.appliedConstraints());
        assertThat(json.valueToTree(saved.get().getResultMetadata().get("planConfirmation")).path("answers"))
                .isEqualTo(json.valueToTree(answers(answer)));
        verify(mapper).selectByIdAndScope(restored.id(), identity.tenantId(), identity.workspaceId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"评分量尺以后确认。", "评分量尺今后再确认。", "评分量尺由我决定。", "评分量尺稍后确认。"})
    void knownMethodRemainsUsableWithoutConfirmingItsIndependentParameter(String pending) {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("methods", "分析方法及评分量尺分别是什么？",
                "采用Arriaga方法；" + pending)));
        assertThat(decisions.knownDecisions()).singleElement().satisfies(value ->
                assertThat(value.answer()).isEqualTo("采用Arriaga方法；"));
        assertThat(decisions.pendingDecisions()).anyMatch(value -> value.answer().contains(pending));
        assertThat(decisions.retrievalQuery("原始任务")).contains("采用Arriaga").doesNotContain(pending);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            QUESTION + "|甲、乙、丙、丁四类。",
            QUESTION + "|本次明确采用甲、乙、丙、丁四类。",
            QUESTION + "|分类由我决定，具体为甲、乙、丙、丁四类。",
            "更新类别时如何处理旧分类？|以后确认新类别时保留旧分类。",
            "生产写入需满足什么条件？|如果用户批准，才写入生产环境。",
            "部署需满足什么条件？|如果以后批准上线，再部署。",
            "分析任务是什么？|评估不确定性。",
            QUESTION + "|之前不确定，现在明确采用四类。",
            "量尺确认有哪些限制？|评分量尺不是以后确认。",
            "应保留哪句原文？|引用：评分量尺以后确认。",
            "要解释哪个示例？|例如：分类由我决定。",
            "分类决定权限与量表的条件是什么？|如果分类由我决定，则沿用五级量表。"
    })
    void actualChoicesAndExecutableRulesAreNotTurnedIntoMissingChoices(String question, String answer) {
        // 普通规则用其实际规则题干，不把答非所问伪装成类别已确认的正例。
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("control", question, answer)));
        assertThat(decisions.pendingDecisions()).isEmpty();
        assertThat(decisions.knownDecisions()).hasSize(1);
    }

    @Test
    void futurePermissionCannotHideASeparateExplicitCurrentChoice() {
        String answer = "如果以后批准，再提出拟议设计。现在明确采用甲、乙、丙、丁四类。";
        var decisions = ConfirmedDecisionSet.from(answers(answer));
        assertThat(decisions.knownDecisions()).singleElement().satisfies(value ->
                assertThat(value.answer()).isEqualTo("现在明确采用甲、乙、丙、丁四类。"));
        assertThat(decisions.pendingDecisions()).isNotEmpty();
    }

    @Test
    void newObjectsConditionsNegationAndExceptionsCannotBorrowAnotherAnswer() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("method", "甲院2025年采用哪种方法？",
                "采用Arriaga方法；乙院2026年方法以后确认。")));
        assertThat(decisions.pendingDecisions()).anySatisfy(value ->
                assertThat(value.answer()).contains("乙院2026年方法以后确认"));
        assertThat(decisions.resolvesChoiceRequest("乙院2026年采用哪种方法？")).isFalse();
        assertThat(decisions.resolvesChoiceRequest("甲院2026年采用哪种方法？")).isFalse();
        var task = task("只委托评分设计，分类由我决定；不得为乙院推荐分类，但甲院例外。");
        var partial = ConfirmedDecisionSet.from(answers("只委托评分设计，分类由我决定；不得为乙院推荐分类，但甲院例外。"));
        assertThat(partial.knownDecisions()).isEmpty();
        assertThat(partial.resolvesChoiceRequest(QUESTION)).isFalse();
        assertThat(task.confirmedDecisions()).singleElement().satisfies(value ->
                assertThat(value.answer()).contains("不得为乙院推荐分类，但甲院例外"));
        assertThat(assemble("只委托评分设计，分类由我决定；不得为乙院推荐分类，但甲院例外。")
                .optimizedPrompt()).contains("不得为乙院推荐分类", "甲院例外");
    }

    @ParameterizedTest
    @ValueSource(strings = {"若今后授权，才给出推荐设计。", "如果未来用户同意，再进行拟议设计。",
            "分类由我来决定。", "分类由我们自行确认。", "当前分类由我决定。", "评分量尺以后确认."})
    void equivalentPostponedPermissionAndReservedChoicesRemainPending(String answer) {
        var decisions = ConfirmedDecisionSet.from(answers(answer));
        assertThat(decisions.knownDecisions()).isEmpty();
        assertThat(decisions.pendingDecisions()).anySatisfy(value -> {
            assertThat(value.questionId()).isEqualTo("q");
            assertThat(value.question()).isEqualTo(QUESTION);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"分类不由我决定。", "分类并非由我决定。", "不良反应分类不是以后确认。"})
    void denyingAReservationDoesNotCreateThatReservation(String answer) {
        assertThat(ConfirmedDecisionSet.from(answers(answer)).pendingDecisions()).isEmpty();
    }

    @Test
    void anIndependentNamedParameterCannotEraseTheFuturePermissionBoundary() {
        String answer = "采用Arriaga方法。如果以后批准，再提出拟议设计。乙院2026年评分量尺以后确认。";
        var task = task(answer);
        assertThat(task.pendingDecisions()).anyMatch(value -> value.answer().contains("如果以后批准，再提出拟议设计"))
                .anyMatch(value -> value.answer().contains("乙院2026年评分量尺以后确认"));
        assertThat(task.pendingDecisions()).allSatisfy(value -> assertThat(value.answer()).doesNotContain("采用Arriaga"));
        assertThat(task.modelInput().pendingDecisions()).anyMatch(value -> value.answer().contains("如果以后批准，再提出拟议设计"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"只委托评分设计，分类由我决定。", "当前只委托评分设计，分类由我决定。",
            "仅委托评分设计；分类由我来决定。"})
    void partialInstructionsDoNotResolveTheirBoundCategoryThroughFormattingOrCurrentPrefixes(String answer) {
        assertThat(ConfirmedDecisionSet.from(answers(answer)).knownDecisions()).isEmpty();
        assertThat(task(answer).modelInput().pendingDecisions()).allSatisfy(value -> assertThat(value.designDelegated()).isFalse());
        assertThat(task(answer).executionRequirements()).anyMatch(value -> value.subject().equals("局部委托要求"));
    }

    @Test
    void deduplicatedPartialInstructionRetainsEverySourceWithoutDelegatingEitherUnchosenObject() {
        var task = EffectiveTaskViewFactory.create(RAW, TemplateCode.AUTO,
                List.of(new PlanAnswer("categories", QUESTION, "只委托评分设计，分类由我决定。"),
                        new PlanAnswer("rounds", "Plan轮次是什么？", "只委托评分设计，轮次由我决定。")), List.of(), false);
        assertThat(task.modelInput().executionRequirements()).filteredOn(value -> value.subject().equals("局部委托要求"))
                .singleElement().satisfies(value -> assertThat(value.sourceQuestionIds()).containsExactly("categories", "rounds"));
        assertThat(task.pendingDecisions()).hasSize(2);
        assertThat(task.modelInput().pendingDecisions()).allSatisfy(value -> assertThat(value.designDelegated()).isFalse());
    }

    @Test
    void currentStateChainIsSavedSeparatelyFromTheImmutableParentEvidence() throws Exception {
        var json = com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var observations = FAILURES.stream().map(answer -> java.util.Map.of("answer", answer,
                "task", task(answer), "modelInput", task(answer).modelInput(), "result", assemble(answer))).toList();
        var output = java.nio.file.Path.of("target", "candidate26-state-chain.json");
        java.nio.file.Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), java.util.Map.of(
                "kind", "OFFLINE_SYNTHETIC_STATE_REPLAY_NOT_NEW_MODEL_INFERENCE", "realModelCalls", 0,
                "observations", observations));
    }

    /** 空模型正文逼出实际组装行为，不借响应正文预先补入未决问题。 */
    private static OptimizationResult assemble(String answer) {
        var sections = List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", "本次交付研究方法正文。"),
                new PromptSection(PromptSectionType.TASK, "任务", "撰写研究方法部分，保留原定评价目标。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付中文方法正文。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不虚构事实。"));
        var response = new EnhancementProviderResponse(sections, "offline-state-replay", "offline", true,
                List.of(), List.of());
        return new OptimizationResultAssembler().assemble(response, EMPTY,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, RAW), List.of(), answers(answer), true,
                new com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl().completeForTask(EMPTY,
                        com.promptoptimizer.enhancement.dto.PermissionPolicyInput.empty(), true,
                        com.promptoptimizer.template.domain.TaskIntentResolver.resolve(TemplateCode.AUTO, RAW)),
                false, 0, RAW, List.of(), List.of(), task(answer));
    }

    private static List<PlanAnswer> answers(String answer) { return List.of(new PlanAnswer("q", QUESTION, answer)); }

    private static EffectiveTaskView task(String answer) {
        return EffectiveTaskViewFactory.create(RAW, TemplateCode.AUTO, answers(answer), List.of(), false);
    }
}
