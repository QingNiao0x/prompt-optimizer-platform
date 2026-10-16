package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.RequirementBoundaryContract;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放十七真实原件发现的两条平台缺项，不调用模型，也不将下游失败改写为平台通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateEighteenReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIFTH = "研究若要求同信息对照，保留各组指定的增强流程，不改写任一组的回答、不另换任务，也不只增加某组修订轮次。";
    private static final String COMPLETE = "同信息对照两组接收并可使用同一原始任务和同一份实际确认信息；保留各组指定的增强流程，不改写回答、不另换任务，也不只增加某组修订轮次。";

    @Test
    void captured267QualityCheckDeclarationReachesTheActualDirectTask() throws Exception {
        var fixture = fixture(267);
        var response = decode(fixture.path("response"));
        assertThat(response.assessment().gaps()).anyMatch(g -> g.kind() == TaskEnhancementAssessment.Kind.QUALITY_CHECK
                && g.completion().contains("评分尺度"));
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        String raw = fixture.path("rawPrompt").asText();
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), true, intent);
        var result = new OptimizationResultAssembler().assemble(response, context,
                new PromptTemplate(intent.templateCode(), "交付原定正文。", "不虚构事实。", "遵守原要求。", intent.deliveryProfile()),
                List.of(), List.of(), false, bundle, false, 1, raw, List.of(), List.of());
        assertThat(result.sections().stream().filter(s -> s.type() == PromptSectionType.TASK).findFirst().orElseThrow().content())
                .contains("提出拟议评分尺度", "可操作定义", "分歧处理规则").doesNotContain("阈值为");
        assertThat(result.optimizedPrompt()).contains("800", "1200", "不提供代码");
        JSON.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/candidate18-trace267.json").toFile(), result);
    }

    @Test
    void captured266CompleteScopedWorkflowIsNotProjectedAgain() throws Exception {
        var fixture = fixture(266);
        var body = decode(fixture.path("response")).sections().stream().map(PromptSection::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(body).contains(COMPLETE.replace("不改写回答", "不改写任一组的回答"));
        assertThat(contract().missingGuidance(body)).doesNotContain(FIFTH)
                .contains("本次问答流程由增强器提出关键问题");
    }

    @ParameterizedTest
    @ValueSource(strings = {"评分尺度由我决定。", "不得推荐评分尺度。", "评分尺度采用1至5分。", "以后确认后再推荐评分尺度。"})
    void qualityCheckDoesNotOverrideCurrentOrFutureReservations(String boundary) throws Exception {
        var fixture = fixture(267);
        String raw = fixture.path("rawPrompt").asText() + boundary;
        var sections = taskSections();
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections,
                EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false), assessment(fixture));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保留当前研究要求。");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_DECISION", "INPUT_OUTPUT", "EDGE_CASE"})
    void unrelatedKindsDoNotBecomeScoringDesign(String kind) throws Exception {
        var fixture = fixture(267);
        var gap = scoringGap(assessment(fixture));
        var sections = taskSections();
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections,
                EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false),
                singleGap(TaskEnhancementAssessment.Kind.valueOf(kind), gap.missing(), gap.completion(), gap.basis()));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保留当前研究要求。");
    }

    @ParameterizedTest
    @ValueSource(strings = {"另一研究：给出拟议评分尺度与操作定义。", "以后确认后再给出评分尺度与操作定义。", "不得给出评分尺度与操作定义。", "如果未来另行批准，再给出评分尺度与操作定义。"})
    void qualityCheckCannotBorrowOtherScopesNegationOrFutureAuthorization(String completion) throws Exception {
        var fixture = fixture(267);
        var gap = scoringGap(assessment(fixture));
        var sections = taskSections();
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections,
                EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false),
                singleGap(TaskEnhancementAssessment.Kind.QUALITY_CHECK, gap.missing(), completion, gap.basis()));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保留当前研究要求。");
    }

    @Test
    void ordinaryQualityCheckDoesNotCreateScoringAndMissingDelegationDoesNotAuthorizeIt() throws Exception {
        var fixture = fixture(267);
        var sections = taskSections();
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections,
                EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false),
                singleGap(TaskEnhancementAssessment.Kind.QUALITY_CHECK, "正文应检查长度。", "给出篇幅校对要求。", "原定篇幅。"));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保留当前研究要求。");
        String withoutDelegation = fixture.path("rawPrompt").asText().replace("请根据原始研究目标提出可开展的推荐设计并说明适用条件", "仅记录未知事项");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections,
                EffectiveTaskViewFactory.create(withoutDelegation, TemplateCode.AUTO, List.of(), List.of(), false), assessment(fixture));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保留当前研究要求。");
    }

    @ParameterizedTest
    @ValueSource(strings = {"> 给出拟议评分尺度与操作定义。", "```text\n给出拟议评分尺度与操作定义。\n```", "其他研究：\n给出拟议评分尺度与操作定义。"})
    void quotedAndForeignQualityCheckInstructionsDoNotAuthorizeTheCurrentTask(String completion) throws Exception {
        var fixture = fixture(267);
        var gap = scoringGap(assessment(fixture));
        var sections = taskSections();
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections,
                EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false),
                singleGap(TaskEnhancementAssessment.Kind.QUALITY_CHECK, gap.missing(), completion, gap.basis()));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保留当前研究要求。");
    }

    @Test
    void futureDataDoesNotTurnACurrentDesignProposalIntoFutureAuthorization() throws Exception {
        var fixture = fixture(267);
        var gap = scoringGap(assessment(fixture));
        var sections = taskSections();
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections,
                EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false),
                singleGap(TaskEnhancementAssessment.Kind.QUALITY_CHECK, gap.missing(),
                        "给出拟议评分尺度与操作定义，未来数据仅用于评估适用性。", gap.basis()));
        assertThat(sections.get(PromptSectionType.TASK).content()).contains("提出拟议评分尺度");
    }

    @ParameterizedTest
    @ValueSource(strings = {"> ", "```text\n", "其他研究：\n", "以后确认：\n", "仅在增加样本时，", "如果以后批准，"})
    void completeWorkflowCannotBeBorrowedFromSourcesOrNewConditions(String prefix) {
        String suffix = prefix.startsWith("```") ? "\n```" : "";
        assertThat(contract().missingGuidance(prefix + COMPLETE + suffix)).contains(FIFTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"保留各组指定的增强流程，", "不改写回答、", "不另换任务，", "也不只增加某组修订轮次"})
    void everyIndependentWorkflowObligationStillNeedsCoverage(String removed) {
        assertThat(contract().missingGuidance(COMPLETE.replace(removed, ""))).contains(FIFTH);
    }

    @Test
    void newExceptionAndDifferentGroupsDoNotCoverTheOriginalWorkflow() {
        assertThat(contract().missingGuidance(COMPLETE.replace("不改写回答", "除甲组外不改写回答"))).contains(FIFTH);
        assertThat(contract().missingGuidance(COMPLETE.replace("同信息对照两组", "另一研究的两组"))).contains(FIFTH);
        assertThat(contract().missingGuidance(COMPLETE)).doesNotContain(FIFTH);
    }

    /** 夹具含真实原件哈希和实际发送的rawPrompt，模型响应不作修订。 */
    private JsonNode fixture(int trace) throws Exception {
        try (var in = getClass().getResourceAsStream("/enhancement/candidate18-trace" + trace + ".json")) { return JSON.readTree(in); }
    }

    private EnhancementProviderResponse decode(JsonNode value) throws Exception {
        return new EnhancementProviderResponse(JSON.readerForListOf(PromptSection.class).readValue(value.path("sections")),
                "captured-offline", "deepseek-flash", false, JSON.readerForListOf(String.class).readValue(value.path("ambiguities")),
                List.of(), JSON.treeToValue(value.path("assessment"), TaskEnhancementAssessment.class));
    }

    private TaskEnhancementAssessment assessment(JsonNode fixture) throws Exception {
        return JSON.treeToValue(fixture.path("response").path("assessment"), TaskEnhancementAssessment.class);
    }

    private TaskEnhancementAssessment.Gap scoringGap(TaskEnhancementAssessment assessment) {
        return assessment.gaps().stream().filter(g -> g.kind() == TaskEnhancementAssessment.Kind.QUALITY_CHECK).findFirst().orElseThrow();
    }

    private TaskEnhancementAssessment singleGap(TaskEnhancementAssessment.Kind kind, String missing, String completion, String basis) {
        return new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(kind, missing, completion, basis)), TaskEnhancementAssessment.Presentation.STRUCTURED);
    }

    private EnumMap<PromptSectionType, PromptSection> taskSections() {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        result.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", "保留当前研究要求。"));
        return result;
    }

    private RequirementBoundaryContract contract() {
        return RequirementBoundaryContract.from("研究Plan问答：增强器提问，由用户回答，再增强提示词。", List.of(), List.of());
    }
}
