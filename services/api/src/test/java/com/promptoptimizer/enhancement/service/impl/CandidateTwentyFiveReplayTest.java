package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
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
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回放325真实响应的委托与评分投影，保护完整对象、未决状态和作用范围；不调用模型或修改原件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateTwentyFiveReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ContextSnapshot EMPTY = new ContextSnapshot("", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "EMPTY");
    private static final String RAW = "撰写中文研究设计的方法部分，说明准确性、完整性、清晰度与可执行性评价以及分歧处理。";
    private static final String DELEGATION = "请根据原始研究目标提出可开展的推荐设计并说明适用条件";
    private static final String DIRECTIVE = DELEGATION + "，所有建议标明为拟议设计，不冒充已确认或已实施。";
    private static final String ANSWER = "这是尚未实施的研究。" + DELEGATION + "；我没有额外真实数据，不确认任何既有参数。";
    private static final String QUESTION = "4 类任务具体指哪四类？";
    private static final String PREFIX = "已委托提出拟议设计，实际采用参数仍未确认：";
    private static final String HEADER = "需在方法部分中落实以下内容：";
    private static final String CATEGORY = "- 4类任务的具体类别建议（如事实性、推理性、开放性、约束性等）及选取依据；20个任务的来源与筛选标准。";
    private static final String MISSING = "随机化、盲法、准确性/完整性/清晰度/可执行性评价及分歧处理的具体操作步骤未展开。";
    private static final String COMPLETION = "在TASK中补充：随机化的拟议分配方式与适用条件；盲评材料的盲化处理方式；四项评价指标各自的操作定义与评分尺度建议；两名评审分歧的处理流程。";
    private static final String SCALE = "针对原要求的准确性、完整性、清晰度与可执行性，提出拟议评分尺度、可操作定义及各等级评分锚点、与尺度相配的分歧处理规则，并说明适用条件。";

    @Test
    void captured325DoesNotAppendAnotherCompleteGenericDelegation() throws Exception {
        var fixture = fixture();
        var result = assemble(fixture);
        assertThat(count(section(result.sections(), PromptSectionType.TASK), DELEGATION)).isEqualTo(1);
        assertThat(section(result.sections(), PromptSectionType.TASK)).contains(DIRECTIVE);
    }

    @Test
    void captured325NaturalCategoryRequirementNeedsNoSecondDesignHeader() throws Exception {
        var result = assemble(fixture());
        assertThat(section(result.sections(), PromptSectionType.TASK)).contains(CATEGORY.substring(2))
                .doesNotContain("对以下已委托设计的子项给出拟议主方案及适用条件");
    }

    @Test
    void captured325ScoringSupplementProjectsExistingObligationWithoutDefaultValues() throws Exception {
        var fixture = fixture();
        String original = fixture.toString();
        var result = assemble(fixture);
        assertThat(count(result.optimizedPrompt(), SCALE)).isEqualTo(1);
        assertThat(result.optimizedPrompt()).doesNotContain("1至5分", "1至7分", "评分采用5分", "评分采用7分");
        assertThat(result.ambiguities()).isEqualTo(JSON.readerForListOf(String.class)
                .readValue(fixture.path("originalPlatformResult").path("ambiguities")));
        assertThat(result.appliedConstraints()).isEqualTo(JSON.readerForListOf(String.class)
                .readValue(fixture.path("originalPlatformResult").path("appliedConstraints")));
        var task = task(fixture.path("rawPrompt").asText(), answers(fixture));
        assertThat(task.pendingDecisions()).hasSize(5);
        assertThat(task.confirmedDecisions()).allMatch(decision -> decision.scope() == ConfirmedPlanDecision.Scope.UNRESOLVED);
        assertThat(fixture.toString()).isEqualTo(original);
        var output = java.nio.file.Path.of("target", "candidate25-trace325-assembled.json");
        java.nio.file.Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    @Test
    void changedCopyAndSourcesRoundTripThroughTheScopedHistoryService() throws Exception {
        var fixture = fixture();
        var request = JSON.treeToValue(fixture.path("actualRequest"), OptimizationRequest.class);
        var result = assemble(fixture);
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
        var restored = history.get(history.save(request, result));
        assertThat(restored.optimizedPrompt()).isEqualTo(result.optimizedPrompt());
        assertThat(restored.sections()).isEqualTo(result.sections());
        assertThat(restored.appliedConstraints()).isEqualTo(result.appliedConstraints());
        assertThat(JSON.valueToTree(saved.get().getResultMetadata().get("planConfirmation")).path("answers"))
                .isEqualTo(fixture.path("actualRequest").path("planConfirmation").path("answers"));
        verify(mapper).selectByIdAndScope(restored.id(), identity.tenantId(), identity.workspaceId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            DIRECTIVE, DELEGATION + "，所有建议均标注为拟议设计，不冒充已确认或已实施。",
            "为研究生撰写方法部分。" + DIRECTIVE, "本任务：\n" + DIRECTIVE, "- " + DIRECTIVE,
            "为研究生撰写方法部分，" + DELEGATION + "。", "本次任务要求：" + DELEGATION + "。"
    })
    void completeCurrentDelegationWithStatusIsNotAppendedAgain(String content) {
        var sections = sections(content);
        PlanDecisionIntegrator.integrate(sections, task(RAW, List.of(new PlanAnswer("q", QUESTION, ANSWER))));
        assertThat(count(sections.get(PromptSectionType.TASK).content(), DELEGATION)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "如果以后批准，" + DIRECTIVE, "另一研究：\n" + DIRECTIVE, "> " + DIRECTIVE,
            "```text\n" + DIRECTIVE + "\n```", "引用：" + DIRECTIVE,
            "不必" + DIRECTIVE, "    " + DIRECTIVE, DELEGATION + "，但仅限甲院2025年样本。",
            DELEGATION + "，以后再说明适用条件。", DELEGATION + "；但仅限甲院2025年样本。"
    })
    void conditionalForeignQuotedAndPartialDirectivesCannotCoverCurrentDelegation(String content) {
        var sections = sections(content);
        PlanDecisionIntegrator.integrate(sections, task(RAW, List.of(new PlanAnswer("q", QUESTION, ANSWER))));
        assertThat(sections.get(PromptSectionType.TASK).content().lines().toList().getLast())
                .startsWith("- " + DELEGATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            CATEGORY, "- 4 类任务的具体类别建议及选取依据。", "- 4类任务的分类框架及选取依据。"
    })
    void aCurrentDelegatedRequirementListCoversItsCompleteNamedCategory(String item) {
        var sections = sections(DIRECTIVE + "\n\n" + HEADER + "\n" + item);
        String before = sections.get(PromptSectionType.TASK).content();
        var task = task(RAW, List.of(new PlanAnswer("q", QUESTION, ANSWER)));
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(before);
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task)).isEmpty();
        assertThat(task.modelInput().executionRequirements()).allMatch(value -> value.sourceQuestionIds().contains("q"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "- 乙院4类任务的具体类别建议及选取依据。", "- 4类任务的具体类别建议，仅限甲院。",
            "- 如果以后获批，再给出4类任务的具体类别建议。", "- 不提供4类任务的具体类别建议。",
            "- 4类任务的具体类别建议不提供。", "- 4类任务的具体类别建议尚未确定。",
            "- 4类任务的具体类别建议；但仅限2025年。", "> " + CATEGORY,
            "```text\n" + CATEGORY + "\n```", "    " + CATEGORY,
            "- <span>4类任务的具体类别建议。</span>", "- \"4类任务的具体类别建议。\""
    })
    void categoryCoverageCannotBorrowOtherObjectsConditionsDenialsOrQuotedMaterial(String item) {
        var sections = sections(DIRECTIVE + "\n" + HEADER + "\n" + item);
        var task = task(RAW, List.of(new PlanAnswer("q", QUESTION, ANSWER)));
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task))
                .containsExactly(PREFIX + QUESTION);
    }

    @Test
    void aSeparateTrailingExceptionAndANewObjectKeepTheirOriginalReminder() {
        var sections = sections(DIRECTIVE + "\n" + HEADER + "\n" + CATEGORY + "\n但上述仅适用于甲院2025年。");
        var task = task(RAW, List.of(new PlanAnswer("q", QUESTION, ANSWER)));
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task))
                .containsExactly(PREFIX + QUESTION);
        String independent = "乙院4类任务具体指哪四类？";
        sections = sections(DIRECTIVE + "\n" + HEADER + "\n" + CATEGORY);
        task = task(RAW, List.of(new PlanAnswer("new-object", independent, ANSWER)));
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + independent), task))
                .containsExactly(PREFIX + independent);
    }

    @Test
    void bulletDelegationIsStillCurrentAndANominalListAloneCannotAuthorizeDesign() {
        var task = task(RAW, List.of(new PlanAnswer("q", QUESTION, ANSWER)));
        var sections = sections("- " + DIRECTIVE + "\n" + HEADER + "\n" + CATEGORY);
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task)).isEmpty();
        sections = sections(HEADER + "\n" + CATEGORY);
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + QUESTION), task))
                .containsExactly(PREFIX + QUESTION);
        String spacedObject = "2 0类任务具体指哪四类？";
        sections = sections(DIRECTIVE + "\n" + HEADER + "\n- 20类任务的具体类别建议及选取依据。");
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, List.of(PREFIX + spacedObject),
                task(RAW, List.of(new PlanAnswer("q", spacedObject, ANSWER))))).containsExactly(PREFIX + spacedObject);
    }

    @ParameterizedTest
    @ValueSource(strings = {COMPLETION, "在任务段落中补充：四项评价指标各自的操作定义与评分尺度建议。",
            "在任务部分中补充：四项评价指标各自的操作定义及评分尺度建议。"})
    void scoringSupplementUsesExistingProjectionOnceAndNeverCopiesMetadataNumbers(String completion) {
        var sections = sections("保留当前方法部分。");
        var task = task(RAW, List.of(new PlanAnswer("q", QUESTION, ANSWER)));
        var assessment = assessment(MISSING, completion);
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment);
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment);
        assertThat(count(sections.get(PromptSectionType.TASK).content(), SCALE)).isEqualTo(1);
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).isEqualTo("不确认任何既有参数。");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "如果以后批准，" + COMPLETION, "另一研究：\n" + COMPLETION, "> " + COMPLETION,
            "```text\n" + COMPLETION + "\n```", "引用：" + COMPLETION,
            "在TASK中不补充：四项评价指标各自的操作定义与评分尺度建议。",
            "在TASK中补充：四项评价指标各自的操作定义与评分尺度建议，但评分量尺由用户决定。",
            "在TASK中补充：若以后确认，四项评价指标各自的操作定义与评分尺度建议。"
    })
    void scoringSupplementRejectsConditionalForeignQuotedDeniedAndReservedDeclarations(String completion) {
        var sections = sections("保留当前方法部分。");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task(RAW, List.of(new PlanAnswer("q", QUESTION, ANSWER))),
                assessment(MISSING, completion));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保留当前方法部分。");
    }

    @ParameterizedTest
    @ValueSource(strings = {"评分尺度由我决定。", "评分尺度以后确认。", "不得推荐评分尺度。", "评分尺度为1至7分。",
            "采用李克特量表，等级数由我决定。"})
    void currentSupplementCannotOverrideTheUsersReservedOrAlreadyChosenScale(String boundary) {
        var sections = sections("保留当前方法部分。");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task(RAW + boundary, List.of(new PlanAnswer("q", QUESTION, ANSWER))),
                assessment(MISSING, COMPLETION));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保留当前方法部分。");
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果以后批准，再提出拟议设计。", "只委托评分设计，分类由我决定。", "评分量尺以后确认。"})
    void modelDeclarationsCannotTurnFutureOrPartialAnswersIntoCurrentFullDelegation(String answer) {
        var task = task(RAW, List.of(new PlanAnswer("q", QUESTION, answer)));
        var sections = sections(DIRECTIVE + "\n" + HEADER + "\n" + CATEGORY);
        String before = sections.get(PromptSectionType.TASK).content();
        var originalView = task.modelInput();
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment(MISSING, COMPLETION));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(before);
        assertThat(task.modelInput()).isEqualTo(originalView);
        // 三种短答的既有CHOICE分类另作未关闭发现；本测试只证明组装不会授予完整当前委托或改写来源。
        assertThat(task.executionRequirements()).noneMatch(requirement -> requirement.subject().equals("委托要求")
                && requirement.statement().startsWith(DELEGATION));
    }

    /** 原件经过生产组装入口；只标记离线来源，不改response正文、assessment或实际确认。 */
    private OptimizationResult assemble(JsonNode fixture) throws Exception {
        String raw = fixture.path("rawPrompt").asText();
        var node = fixture.path("response");
        var response = new EnhancementProviderResponse(JSON.readerForListOf(PromptSection.class).readValue(node.path("sections")),
                "offline-replay-325", "deepseek-flash", false,
                JSON.readerForListOf(String.class).readValue(node.path("ambiguities")), List.of(),
                normalizedAssessment(node));
        var answers = answers(fixture);
        return new OptimizationResultAssembler().assemble(response, EMPTY,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, true,
                new ConstraintCompleterImpl().completeForTask(EMPTY, PermissionPolicyInput.empty(), true,
                        TaskIntentResolver.resolve(TemplateCode.AUTO, raw)), false, 0, raw, List.of(), List.of(), task(raw, answers));
    }

    /** 实际题答保持原始五个ID和原文；测试不创造实际参数确认。 */
    private List<PlanAnswer> answers(JsonNode fixture) throws Exception {
        return JSON.readerForListOf(PlanAnswer.class).readValue(fixture.path("actualAnswers"));
    }

    /**
     * 325线协议没有内部presentation；复用生产Provider已有归一化后再进入组装DTO。
     * 只处理副本，非法枚举仍由同一record校验，原始响应和缺项声明不被补写。
     */
    private TaskEnhancementAssessment normalizedAssessment(JsonNode response) throws Exception {
        var payload = response.deepCopy();
        var providerType = com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider.class;
        var provider = new com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider(
                mock(org.springframework.web.client.RestClient.class), JSON,
                new com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties());
        var normalize = providerType.getDeclaredMethod("normalizeMissingPresentation", JsonNode.class);
        normalize.setAccessible(true);
        normalize.invoke(provider, payload);
        return JSON.treeToValue(payload.path("assessment"), TaskEnhancementAssessment.class);
    }

    /** 从显式夹具读取325原件派生数据，其来源哈希另行校验。 */
    private JsonNode fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/enhancement/candidate25-trace325.json")) {
            return JSON.readTree(stream);
        }
    }

    /** 用真实任务视图保证作用范围及未决状态参与测试。 */
    private EffectiveTaskView task(String raw, List<PlanAnswer> answers) {
        return EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of(), false);
    }

    /** 责任段和全称未确认边界共同决定能否省去委托提醒。 */
    private EnumMap<PromptSectionType, PromptSection> sections(String task) {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        result.put(PromptSectionType.BACKGROUND, new PromptSection(PromptSectionType.BACKGROUND, "背景", "本研究尚未实施。"));
        result.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", task));
        result.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", "交付方法部分。"));
        result.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不确认任何既有参数。"));
        return result;
    }

    /** 补充声明只提供缺项信号，不携带平台默认量表或用户确认。 */
    private TaskEnhancementAssessment assessment(String missing, String completion) {
        return new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.EXECUTION_STEP, missing, completion,
                        "原始任务要求四维评价和分歧处理，已委托拟议设计。")), TaskEnhancementAssessment.Presentation.STRUCTURED);
    }

    /** 按实际责任段核对重复，不把展示提醒或验收提及当执行内容。 */
    private String section(List<PromptSection> sections, PromptSectionType type) {
        return sections.stream().filter(value -> value.type() == type).findFirst().orElseThrow().content();
    }

    /** 精确计数完整执行片段，独立条件和对象通过对应反例核对。 */
    private int count(String text, String value) {
        return text.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
