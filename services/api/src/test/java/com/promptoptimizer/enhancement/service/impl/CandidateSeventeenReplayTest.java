package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 254原件与240/241缺项的正式组装回放；区分原始模型内容和本地构造反例。 */
class CandidateSeventeenReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RULE = "研究地区、数据来源在现有资料中未提供，正文中相应位置标注未知，不虚构数据或结论。";
    private static final String SCALE_RAW = "撰写中文研究设计的方法部分。说明准确性、完整性、清晰度与可执行性评价以及分歧处理。请根据原始研究目标提出可开展的推荐设计并说明适用条件。";

    @Test
    void captured254DoesNotRepeatTheSameMaterialStatusOrAttributeTableExamplesToTheUser() throws Exception {
        JsonNode fixture = fixture(254);
        var response = decode(fixture.path("response"));
        var result = assemble(fixture.path("rawPrompt").asText(), response);
        var background = section(result, PromptSectionType.BACKGROUND);
        assertThat(background).contains("2015", "心脑血管", "Arriaga").doesNotContain("在现有资料中未提供");
        assertThat(section(result, PromptSectionType.CONSTRAINTS)).contains("参考寿命表", "在现有资料中未提供");
        assertThat(section(result, PromptSectionType.OUTPUT)).doesNotContain("用户明确要求的表格（如年度YLL、性别YLL率）");
        assertThat(result.ambiguities()).contains(response.ambiguities().get(0), response.ambiguities().get(1),
                response.ambiguities().get(3), response.ambiguities().get(4)).doesNotContain(response.ambiguities().get(2));
        assertThat(result.optimizedPrompt()).contains("拟议方法", "不铺空结果表", "Arriaga", "YLL率");
        JSON.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/candidate17-trace254.json").toFile(), result);
    }

    @Test
    void exactCompleteMaterialStatusHasOneExecutionLocation() {
        var result = assemble("撰写研究分析报告。", response("本任务分析疾病死亡率。" + RULE, "交付正文。", RULE));
        assertThat(section(result, PromptSectionType.BACKGROUND)).isEqualTo("本任务分析疾病死亡率。");
        assertThat(section(result, PromptSectionType.CONSTRAINTS)).contains(RULE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"甲医院研究地区", "2025年研究地区", "另一研究的研究地区", "未来研究地区", "除外地区", "研究地区在取得授权后"})
    void independentObjectsConditionsAndExceptionsRemain(String object) {
        String independent = object + "、数据来源在现有资料中未提供，正文中相应位置标注未知，不虚构数据或结论。";
        assertThat(section(assemble("撰写研究分析报告。", response(independent, "交付正文。", RULE)), PromptSectionType.BACKGROUND))
                .contains(independent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"请提供年度YLL表。", "请提供性别YLL率表格。", "请交付指标表，不要虚构数值。"})
    void anExplicitTableRemainsRequiredWithOrWithoutMaterials(String instruction) {
        String output = "用户明确要求的表格（如年度YLL、性别YLL率）在正文中保留表结构，数值留待数据补充。";
        for (String data : List.of("", "实际数据已在原文给出。")) {
            assertThat(section(assemble("撰写研究报告。" + instruction + data, response("研究任务。", output, "忠于资料。")),
                    PromptSectionType.OUTPUT)).contains("表格", "保留表结构", "数值留待数据补充");
        }
    }

    @Test
    void tableNamesInQuotesDoNotCreateAUserRequest() {
        String output = "用户明确要求的表格（如年度YLL、性别YLL率）在正文中保留表结构，数值留待数据补充。";
        var result = assemble("撰写分析报告。\n> 请提供年度YLL表。", response("研究任务。", output, "忠于资料。"));
        assertThat(section(result, PromptSectionType.OUTPUT)).doesNotContain("（如年度YLL、性别YLL率）").contains("表格");
    }

    @Test
    void directOriginalDelegationAllowsOnlyTheDeclaredScoringObligation() {
        var sections = sections("说明评价与分歧处理。");
        var task = EffectiveTaskViewFactory.create(SCALE_RAW, TemplateCode.AUTO, List.of(), List.of());
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment());
        assertThat(sections.get(PromptSectionType.TASK).content()).contains("提出拟议评分尺度", "可操作定义", "分歧处理规则")
                .doesNotContain("1至5", "阈值为");
    }

    @Test
    void captured241OriginalDelegationAndDeclaredGapReachTheActualDirectBody() throws Exception {
        var fixture = fixture(241);
        var result = assemble(fixture.path("rawPrompt").asText(), decode(fixture.path("response")));
        assertThat(section(result, PromptSectionType.TASK)).contains("提出拟议评分尺度", "可操作定义", "分歧处理规则");
        assertThat(result.optimizedPrompt()).contains("800", "1200", "不提供代码").doesNotContain("阈值为");
        JSON.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/candidate17-trace241.json").toFile(), result);
    }

    @Test
    void newCapturedBodiesSurviveTheActualHistoryProjectionWithoutLosingSources() throws Exception {
        for (int trace : List.of(254, 241)) {
            var fixture = fixture(trace);
            var result = assemble(fixture.path("rawPrompt").asText(), decode(fixture.path("response")));
            var actor = com.promptoptimizer.identity.support.TestActors.currentActor();
            var identity = actor.require();
            var mapper = mock(com.promptoptimizer.history.mapper.OptimizationRecordMapper.class);
            var persistence = mock(com.promptoptimizer.history.service.impl.OptimizationHistoryPersistenceService.class);
            var saved = new java.util.concurrent.atomic.AtomicReference<com.promptoptimizer.history.entity.OptimizationRecordEntity>();
            when(persistence.persist(any(), eq(identity))).thenAnswer(invocation -> {
                com.promptoptimizer.history.entity.OptimizationRecordEntity entity = invocation.getArgument(0);
                entity.setCreatedAt(java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z"));
                saved.set(entity);
                return entity.getId();
            });
            when(mapper.selectByIdAndScope(any(), eq(identity.tenantId()), eq(identity.workspaceId()))).thenAnswer(invocation -> saved.get());
            var history = new com.promptoptimizer.history.service.impl.MybatisOptimizationHistoryService(mapper, persistence,
                    mock(com.promptoptimizer.enhancement.service.EnhancementOrchestrator.class), actor,
                    com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
            var request = new com.promptoptimizer.enhancement.dto.OptimizationRequest(fixture.path("rawPrompt").asText(),
                    new com.promptoptimizer.context.dto.ContextAnalysisRequest("", List.of()),
                    com.promptoptimizer.enhancement.dto.EnhancementOptions.defaults(), List.of(), PermissionPolicyInput.empty());
            var restored = history.get(history.save(request, result));
            assertThat(restored.optimizedPrompt()).isEqualTo(result.optimizedPrompt());
            assertThat(restored.sections()).isEqualTo(result.sections());
            assertThat(restored.appliedConstraints()).isEqualTo(result.appliedConstraints());
            assertThat(restored.ambiguities()).isEqualTo(result.ambiguities());
        }
    }

    @Test
    void captured240InformationParityIsNotAddedAgainButOtherMissingWorkflowRulesRemain() throws Exception {
        var captured = fixture(240);
        var contract = RequirementBoundaryContract.from(captured.path("rawPrompt").asText(), List.of(), List.of());
        var body = decode(captured.path("response")).sections().stream().map(PromptSection::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(contract.missingGuidance(body)).doesNotContain("研究若要求同信息对照，两组接收并可使用")
                .contains("不改写任一组的回答");
    }

    @Test
    void compactionLeavesQuotedIdenticalSourceAndDifferentHandlingIntact() {
        String background = "研究背景。" + RULE + "\n> " + RULE;
        var result = assemble("撰写研究分析报告。", response(background, "交付正文。", RULE));
        assertThat(section(result, PromptSectionType.BACKGROUND)).isEqualTo("研究背景。\n> " + RULE);
        String restricted = RULE.replace("不虚构数据或结论", "仅限内部核对，不用于正文");
        assertThat(section(assemble("撰写研究分析报告。", response(restricted, "交付正文。", RULE)),
                PromptSectionType.BACKGROUND)).contains(restricted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"> ", "```text\n", "其他研究：\n", "以后确认：\n", "其他研究：\n```text\n本任务：\n```\n"})
    void conditionExamplesInSourceOrForeignScopeAreNotRewritten(String prefix) {
        String text = prefix + "用户明确要求的表格（如年度YLL、性别YLL率）保留表结构。";
        var result = assemble("撰写分析报告。", response("本次分析。", text, "不虚构事实。"));
        assertThat(section(result, PromptSectionType.OUTPUT)).contains(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"时间序列长期趋势和季节性趋势拟采用哪种统计方法？", "长期趋势与季节性趋势分析选择哪些方法？", "本次时间序列季节性趋势使用什么分析方法？"})
    void routineTrendMethodsAreProposedWithoutFabricatingUserConfirmation(String text) {
        var question = new PlanQuestion("method", text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        var raw = "分析时间序列长期趋势和季节性趋势。";
        var input = new PlanningProviderRequest(raw, "", List.of(), null, null, List.of());
        assertThat(new PlanQuestionFilter().filter(List.of(question), input)).isEmpty();
        var reserved = new PlanningProviderRequest(raw + "先让我确认方法。", "", List.of(), null, null, List.of());
        assertThat(new PlanQuestionFilter().filter(List.of(question), reserved)).containsExactly(question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025年新增条件", "甲医院的具体方法", "时间粒度尚未确定", "标准人口待选择", "实际采用参数需用户确认", "如果另有数据应如何选择", "预注册方案的既有方法"})
    void aPureMethodTitleCannotEraseIndependentConditionsInHints(String hint) {
        var question = new PlanQuestion("method", "时间序列长期趋势和季节性趋势拟采用哪种统计方法？", hint,
                PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        var input = new PlanningProviderRequest("分析时间序列长期趋势和季节性趋势。", "", List.of(), null, null, List.of());
        assertThat(new PlanQuestionFilter().filter(List.of(question), input)).containsExactly(question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果以后", "另一研究：\n", "> "})
    void workflowCoverageCannotBeBorrowedFromFutureOrOtherSources(String prefix) {
        var contract = RequirementBoundaryContract.from("Plan问答由用户回答后增强提示词。", List.of(), List.of());
        String obligation = "研究若要求同信息对照，两组接收并可使用同一原始任务和同一份实际确认信息。";
        assertThat(contract.missingGuidance(prefix + obligation)).contains(obligation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"评分尺度由我决定。", "不得推荐评分尺度。", "评分尺度采用1至5分。", "以后确认后再推荐评分尺度。"})
    void originalDelegationDoesNotOverrideScaleReservation(String boundary) {
        var sections = sections("保留原要求。");
        var task = EffectiveTaskViewFactory.create(SCALE_RAW + boundary, TemplateCode.AUTO, List.of(), List.of());
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment());
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo("保留原要求。");
    }

    @Test
    void compositeWorkflowOnlyAppendsItsMissingIndependentObligation() {
        var contract = RequirementBoundaryContract.from("研究Plan问答：增强器提问，由用户回答，再增强提示词。", List.of(), List.of());
        String first = contract.guidance().split("。", -1)[0] + "。";
        String missing = contract.missingGuidance(first);
        assertThat(missing).doesNotContain(first).contains("Plan不能改为");
        assertThat(contract.missingGuidance(contract.guidance())).isEmpty();
    }

    @Test
    void requestsActionGetsEngineeringRulesButExplanatoryMentionDoesNot() {
        for (String raw : List.of("使用requests库爬取网页标题。", "用 BeautifulSoup 库提取网页标题。", "Use requests to fetch a page title.")) {
            assertThat(TaskIntentResolver.resolve(TemplateCode.AUTO, raw).engineeringConstraints()).as(raw).isTrue();
        }
        for (String raw : List.of("介绍requests库的用途。", "撰写一篇说明requests如何爬取网页标题的文章。", "翻译：Use requests to fetch a page title.", "不使用requests库爬取网页标题。",
                "使用requests库爬取网页标题，不提供代码。", "使用requests库爬取网页标题。\n不提供代码。", "使用requests库爬取网页标题，只解释思路。",
                "Use requests to fetch a page title, do not provide code.", "Use requests to fetch a page title. Only explain the approach.")) {
            assertThat(TaskIntentResolver.resolve(TemplateCode.AUTO, raw).engineeringConstraints()).as(raw).isFalse();
        }
        assertThat(TaskIntentResolver.resolve(TemplateCode.AUTO, "使用requests库爬取网页标题。\n> 不提供代码。").engineeringConstraints()).isTrue();
    }

    private JsonNode fixture(int trace) throws Exception {
        try (var in = getClass().getResourceAsStream("/enhancement/candidate17-trace" + trace + ".json")) { return JSON.readTree(in); }
    }

    /** 不修改模型原文；只解码已保存的结构化响应，使用正式组装入口。 */
    private EnhancementProviderResponse decode(JsonNode json) throws Exception {
        return new EnhancementProviderResponse(JSON.readerForListOf(PromptSection.class).readValue(json.path("sections")),
                "captured-offline", "deepseek-flash", false, JSON.readerForListOf(String.class).readValue(json.path("ambiguities")),
                List.of(), JSON.treeToValue(json.path("assessment"), TaskEnhancementAssessment.class));
    }

    private OptimizationResult assemble(String raw, EnhancementProviderResponse response) {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), true, intent);
        return new OptimizationResultAssembler().assemble(response, context,
                new PromptTemplate(intent.templateCode(), "交付原定正文。", "不虚构事实。", intent.deliveryProfile()),
                List.of(), List.of(), false, bundle, 1, raw, List.of(), List.of());
    }

    private EnhancementProviderResponse response(String background, String output, String constraints) {
        return new EnhancementProviderResponse(List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", background),
                new PromptSection(PromptSectionType.TASK, "任务", "撰写有实质内容的分析报告正文。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints)), "fixture", "fixture", false, List.of());
    }

    private String section(OptimizationResult result, PromptSectionType type) {
        return result.sections().stream().filter(s -> s.type() == type).findFirst().orElseThrow().content();
    }

    private EnumMap<PromptSectionType, PromptSection> sections(String content) {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        result.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", content));
        return result;
    }

    private TaskEnhancementAssessment assessment() {
        return new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION,
                        "评分尺度缺失。", "提出拟议评分量表与操作定义。", "原要求。")), TaskEnhancementAssessment.Presentation.STRUCTURED);
    }
}
