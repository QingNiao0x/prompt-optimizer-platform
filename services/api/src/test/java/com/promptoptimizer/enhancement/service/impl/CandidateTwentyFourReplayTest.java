package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.history.service.impl.MybatisOptimizationHistoryService;
import com.promptoptimizer.history.service.impl.OptimizationHistoryPersistenceService;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.provider.domain.TaskMeaningContract;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回放321／322原件的规则追加、评价对象和委托范围，不产生新模型作品或确认参数。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateTwentyFourReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ContextSnapshot EMPTY = new ContextSnapshot("", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "EMPTY");
    private static final String SOURCE = "明确数据来源、研究对象、指标定义和统计口径，无法核实的数据与引用不得编造。";
    private static final String METHOD = "说明缺失数据、偏倚、不确定性和方法适用条件，保证分析过程可复现。";

    @Test
    void captured322KeepsEachCompleteTaskRuleOnceAndRetainsEverySource() throws Exception {
        var fixture = fixture();
        String unchanged = fixture.toString();
        var response = response(fixture);
        String originalConstraints = response.sections().stream().filter(section ->
                section.type() == PromptSectionType.CONSTRAINTS).findFirst().orElseThrow().content();
        var result = assemble(fixture);
        assertThat(count(originalConstraints, SOURCE)).isEqualTo(1);
        assertThat(count(originalConstraints, METHOD)).isEqualTo(1);
        assertThat(count(result.optimizedPrompt(), SOURCE)).isEqualTo(1);
        assertThat(count(result.optimizedPrompt(), METHOD)).isEqualTo(1);
        assertThat(result.appliedConstraints()).contains(SOURCE, METHOD);
        // 已有平台强制规则会移至专用区块；核对全部完整原句，不错误要求其原段落位置不变。
        for (String sentence : originalConstraints.split("(?<=。)")) {
            assertThat(result.optimizedPrompt()).contains(sentence);
        }
        assertThat(fixture.toString()).isEqualTo(unchanged);
        var output = java.nio.file.Path.of("target", "candidate24-trace322-assembled.json");
        java.nio.file.Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    @Test
    void actual322AssemblyRoundTripsBodySectionsAndPlanSourcesThroughScopedHistory() throws Exception {
        var fixture = fixture();
        var result = assemble(fixture);
        var request = JSON.treeToValue(fixture.path("actualRequest"), OptimizationRequest.class);
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
                mock(com.promptoptimizer.enhancement.service.EnhancementOrchestrator.class), actor,
                com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
        var id = history.save(request, result);
        var restored = history.get(id);
        assertThat(restored.optimizedPrompt()).isEqualTo(result.optimizedPrompt());
        assertThat(restored.sections()).isEqualTo(result.sections());
        assertThat(restored.appliedConstraints()).isEqualTo(result.appliedConstraints()).contains(SOURCE, METHOD);
        assertThat(saved.get().getResultMetadata()).containsKey("planConfirmation");
        var confirmation = JSON.valueToTree(saved.get().getResultMetadata().get("planConfirmation"));
        assertThat(confirmation.path("answers")).isEqualTo(fixture.path("actualRequest").path("planConfirmation").path("answers"));
        verify(mapper).selectByIdAndScope(id, identity.tenantId(), identity.workspaceId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "不确认任何既有参数。%s%s",
            "%s%s保留原任务交付。",
            "本任务：\n%s%s",
            "%s\n%s",
            "- 不确认任何既有参数。%s%s"
    })
    void independentCompleteSentencesMayShareAParagraphWithoutBeingAppendedAgain(String layout) {
        assertThat(TaskConstraintCoverage.uncovered(layout.formatted(SOURCE, METHOD), List.of(SOURCE, METHOD)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "如果资料齐全，%s%s",
            "若授权通过，%s%s",
            "假设已获得授权。%s%s",
            "本条仅适用于甲研究。%s%s",
            "乙研究的要求如下。%s%s",
            "另一任务的要求。%s%s",
            "以后确认采用以下规则。%s%s",
            "不必遵守以下要求。%s%s",
            "%s%s但演示资料除外。",
            "%s%s例外为公开材料。",
            "示例：%s%s",
            "%s%s是否适用于本次任务？",
            "<!--%s%s-->",
            "“%s%s”",
            "`%s%s`",
            "> %s%s",
            "| %s%s |",
            "    %s%s",
            "```text\n%s%s\n```",
            "其他研究：\n%s%s",
            "如果以后确认：\n%s%s"
    })
    void paragraphSplittingCannotBorrowConditionalForeignQuotedOrExceptedRules(String layout) {
        assertThat(TaskConstraintCoverage.uncovered(layout.formatted(SOURCE, METHOD), List.of(SOURCE, METHOD)))
                .containsExactly(SOURCE, METHOD);
    }

    @Test
    void completeSentencesDoNotCoverPartialRulesNewObjectsOrChangedLiteralValues() {
        var independent = "乙研究" + SOURCE;
        var conditional = SOURCE.replace("无法核实", "获准后无法核实");
        assertThat(TaskConstraintCoverage.uncovered(SOURCE + METHOD,
                List.of(SOURCE, METHOD, independent, conditional))).containsExactly(independent, conditional);
        assertThat(TaskConstraintCoverage.uncovered("明确数据来源、研究对象、指标定义和统计口径。" + METHOD,
                List.of(SOURCE, METHOD))).containsExactly(SOURCE);
        assertThat(TaskConstraintCoverage.uncovered("状态必须为ready;done。阈值必须>=1.5。",
                List.of("状态必须为ready,done。", "阈值必须>1.5。")))
                .containsExactly("状态必须为ready,done。", "阈值必须>1.5。");
    }

    @Test
    void aLongLeadingConditionStillScopesTheWholeParagraph() {
        String condition = "完成材料核对及相关程序".repeat(8) + "后，";
        assertThat(TaskConstraintCoverage.uncovered(condition + SOURCE + METHOD, List.of(SOURCE, METHOD)))
                .containsExactly(SOURCE, METHOD);
    }

    @Test
    void originalEvaluationObjectAndExistingGuidanceAreBoundWithoutNewDefaultParameters() throws Exception {
        String raw = fixture().path("rawPrompt").asText();
        var meaning = TaskMeaningContract.from(raw, List.of());
        assertThat(meaning.evaluationTargets()).extracting(TaskMeaningContract.EvaluationTarget::text)
                .containsExactly("提示词");
        assertThat(meaning.evaluationGuidance()).contains("评审所见材料", "不能替换评分对象", "适用条件");
        assertThatCode(() -> meaning.validate("评价对象为提示词。下游作品仅作为验证依据。", "sections.TASK"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> meaning.validate("评审对象为下游作品。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
        var result = assemble(fixture());
        assertThat(result.optimizedPrompt()).contains("Plan问答对提示词质量的影响", "各等级评分锚点",
                "实际采用参数仍未确认").doesNotContain("评审者仅见提示词文本");
    }

    @Test
    void captured321DoesNotTreatUserDelegationAsActualParameterSelection() throws Exception {
        var fixture = fixture();
        List<PlanQuestion> questions = JSON.readerForListOf(PlanQuestion.class).readValue(fixture.path("questions"));
        String raw = fixture.path("rawPrompt").asText();
        var remaining = new PlanQuestionFilter().filter(questions, new PlanningProviderRequest(raw, "", List.of()));
        assertThat(remaining).extracting(PlanQuestion::id).containsExactly("plan_interaction_rounds", "task_categories",
                "task_source", "control_group_flow", "deliverable_scope");
        var answers = answers(fixture);
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of());
        assertThat(task.pendingDecisions()).hasSize(5);
        assertThat(task.confirmedDecisions()).allMatch(decision -> decision.scope() == ConfirmedPlanDecision.Scope.UNRESOLVED);
        var result = assemble(fixture);
        assertThat(result.ambiguities()).hasSize(5);
        assertThat(result.optimizedPrompt()).contains("给出拟议主方案", "不冒充已确认或已实施");
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果以后确认，再提出拟议设计。", "只委托评分设计，Plan轮次由我决定。"})
    void futureOrPartialDelegationDoesNotResolveTheOriginalRoundChoice(String answer) throws Exception {
        var fixture = fixture();
        var answers = new java.util.ArrayList<>(answers(fixture));
        var original = answers.getFirst();
        answers.set(0, new PlanAnswer(original.questionId(), original.question(), answer));
        String raw = fixture.path("rawPrompt").asText();
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of());
        var input = new PlanningProviderRequest(raw, "", List.of(), null, null, List.of(), "", task);
        var question = new PlanQuestion(original.questionId(), original.question(), "", PlanQuestionType.FREE_TEXT,
                List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(question), input)).containsExactly(question);
    }

    @Test
    void sameQuestionIdCannotDelegateANewObjectOrNewCondition() throws Exception {
        var fixture = fixture();
        String raw = fixture.path("rawPrompt").asText();
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers(fixture), List.of());
        var input = new PlanningProviderRequest(raw, "", List.of(), null, null, List.of(), "", task);
        var question = new PlanQuestion("plan_interaction_rounds", "另一研究的Plan轮次如何确定？",
                "若新增医院样本，是否需要伦理授权？", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(question), input)).containsExactly(question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"研究地区是哪里？", "死亡率采用何种分母？", "年龄标准化采用哪年标准人口？",
            "另一研究的评分量尺如何确定？", "若新增儿童人群，是否改用其他标准人口？"})
    void actualDataIndependentObjectsAndNewConditionsRemainQuestions(String text) {
        var question = new PlanQuestion("independent", text, "", PlanQuestionType.FREE_TEXT,
                List.of(), List.of(), true);
        String raw = "撰写尚未实施的研究设计方法部分，说明年龄标准化与死亡率分析。采用Arriaga分解方法。"
                + "请根据原始研究目标提出可开展的推荐设计并说明适用条件。";
        assertThat(new PlanQuestionFilter().filter(List.of(question), new PlanningProviderRequest(raw, "", List.of())))
                .containsExactly(question);
    }

    /** 经过真实组装入口保留原题、有效题答和任务约束，不直接构造期望复制正文。 */
    private OptimizationResult assemble(JsonNode fixture) throws Exception {
        String raw = fixture.path("rawPrompt").asText();
        var answers = answers(fixture);
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of());
        return new OptimizationResultAssembler().assemble(response(fixture), EMPTY,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, true,
                new ConstraintCompleterImpl().completeForTask(EMPTY, PermissionPolicyInput.empty(), true,
                        TaskIntentResolver.resolve(TemplateCode.AUTO, raw)), 0, raw, List.of(), List.of(), task);
    }

    /** 实际响应只换离线来源标签；不改正文、assessment或提醒。 */
    private EnhancementProviderResponse response(JsonNode fixture) throws Exception {
        var node = fixture.path("response");
        return new EnhancementProviderResponse(JSON.readerForListOf(PromptSection.class).readValue(node.path("sections")),
                "offline-replay", "deepseek-flash", false,
                JSON.readerForListOf(String.class).readValue(node.path("ambiguities")), List.of(),
                JSON.treeToValue(node.path("assessment"), TaskEnhancementAssessment.class));
    }

    /** 原始逐字合成委托只绑定实际五题，不额外选择任何候选参数。 */
    private List<PlanAnswer> answers(JsonNode fixture) throws Exception {
        return JSON.readerForListOf(PlanAnswer.class).readValue(fixture.path("actualAnswers"));
    }

    /** 测试夹具记录可回查的原件SHA256；原件不随测试修改。 */
    private JsonNode fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/enhancement/candidate24-traces321-322.json")) {
            return JSON.readTree(stream);
        }
    }

    /** 精确计数区分增强原件与平台新追加的副本，不以总篇幅或标题数量代替。 */
    private int count(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
