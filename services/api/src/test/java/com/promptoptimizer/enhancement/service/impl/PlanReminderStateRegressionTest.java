package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放真实提醒、已知授权重问和确认后旧状态，并保留跨对象、新条件和未知答案对照。
 * 通过真实过滤与结果组装入口验证，不以字符串相似度替代业务身份。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanReminderStateRegressionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);
    private static final String REGION_QUESTION = "当某条候选的地区信息缺失、无法核验是否符合当前用户所属地区条件时，这条候选应如何处理？";
    private static final String REGION_ANSWER = "暂不确定，保留为未决前提，实施前先确认；不得绕过地区匹配条件，无法核验不能作为可补值候选。";
    private static final String REGION_REMINDER = "地区信息缺失、无法核验是否符合当前用户所属地区条件的候选应如何处理，目前尚未决定（依据 `materials/software/current-brief.md` 中“地区缺失不是地区匹配成功”及用户确认的未决回答）。实施前需确认该候选是直接排除、标记为待人工核验，还是其他处理方式；不得绕过地区匹配条件，无法核验不能作为可补值候选，也不得默认补全。";
    private static final String CONFLICT = "资料对“科研标准中心”存在不同取值：materials/legal/审批现行说明.md（审批金额 > 50000元时二级复核）与 materials/legal/审批未批草稿.md（审批金额 >= 50000元时二级复核）。请确认本次采用哪一项。";
    private static final String SELECTED = "本次采用科研标准中心现行审批说明：金额严格大于50000元才二级复核；等于50000元不复核；未批准的>=50000元草稿不作为本次执行规则。";
    private static final String RAW = "科研标准中心合同审批存在两个尚未选定的版本：现行说明要求金额严格大于50000元才进入二级复核；未批准草稿要求金额大于或等于50000元进入二级复核。当前尚未确认采用哪版，不自行折中。退款审核标准尚未提供，不能把审批门槛当成退款标准。材料均未签署，尚不代表已生效合同。";

    @Test
    void shouldMergeTheCapturedLongCandidateReminderAndKeepItsExplanation() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("Q1", REGION_QUESTION, REGION_ANSWER)));
        var result = new PlanAmbiguityMerger(decisions).merge(List.of(REGION_REMINDER), List.of(), List.of());
        assertThat(result.executionPrerequisites()).singleElement().asString()
                .contains("标记为待人工核验", "不得绕过地区匹配条件", "无法核验不能作为可补值候选");
    }

    @ParameterizedTest
    @ValueSource(strings = {"地区信息缺失超过30日", "患者所属地区信息缺失", "地区信息不缺失", "地区信息缺失且金额>=50000元"})
    void shouldKeepNewConditionsAndDifferentRegionSubjects(String condition) {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("Q1", REGION_QUESTION, REGION_ANSWER)));
        var result = new PlanAmbiguityMerger(decisions).merge(
                List.of(REGION_REMINDER.replace("地区信息缺失、", condition + "、")), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2).anyMatch(value -> value.contains(condition));
    }

    @Test
    void shouldNotMergeAnotherDecisionHiddenAfterTheSameCandidateHeading() {
        String extra = REGION_REMINDER + "另外，退款资料能否共享尚未获批准，需确认。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("Q1", REGION_QUESTION, REGION_ANSWER)));
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(extra), List.of(), List.of()).executionPrerequisites())
                .hasSize(2).anyMatch(value -> value.contains("退款资料能否共享"));
    }

    @Test
    void shouldInheritKnownUnapprovedSharingAndKeepOtherUnknownHospitalQuestions() {
        var questions = List.of(question("sharing", "跨院共享原始记录是否已获批准？"),
                question("format", "乙医院预约登记资料的文件格式是什么？"),
                question("mapping", "乙医院预约状态映射是什么？"));
        assertThat(plan("比较两家医院资料。是否允许跨院共享原始记录尚未获批准；不能把技术上可合并理解为已获授权。乙医院数据格式和状态映射尚未提供。",
                List.of(), questions)).extracting(PlanQuestion::id).containsExactly("format", "mapping");
    }

    @Test
    void shouldUseActualSharingEvidenceFromUploadedMaterial() {
        assertThat(plan("整理甲乙医院行政资料，保留实际授权边界。", List.of(
                file("materials/hospital/共享说明.md", "跨院原始记录共享：未批准。")),
                List.of(question("sharing", "跨院共享原始记录是否已获批准？")))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"本次整理方案中，跨院共享原始记录是否已获得授权？", "本次方案中，跨院共享原始记录是否已经获得批准？", "跨院共享原始记录是否获得授权？", "目前，跨院共享原始记录是否已经获批准？", "当前，跨院原始记录共享是否已获得授权？"})
    void shouldInheritCurrentAuthorizationDespitePureQuestionPreambles(String text) {
        assertThat(plan("跨院共享原始记录尚未获批准，各院分别处理。", List.of(),
                List.of(question("sharing", text)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"未提供", "尚未提供", "尚未确定", "未指定", "暂不确定"})
    void shouldNotUseAMissingValueLabelAsTheAnswerToAnUnknownFact(String status) {
        assertThat(plan("整理乙医院资料，不得猜测文件格式。", List.of(
                file("materials/hospital/乙医院说明.md", "乙医院数据格式：" + status + "。")),
                List.of(question("format", "乙医院数据格式是什么？"))))
                .extracting(PlanQuestion::id).containsExactly("format");
    }

    @ParameterizedTest
    @ValueSource(strings = {"未来申请新增跨院共享授权应包含哪些字段？", "丙医院共享原始记录是否已获批准？", "跨院共享汇总统计是否已获批准？", "跨院共享原始记录是否已获批准，以及如何申请新的授权？"})
    void shouldKeepNewAuthorizationObjectsAndFutureDecisions(String text) {
        assertThat(plan("跨院共享原始记录尚未获批准。请整理甲乙两家医院资料。", List.of(), List.of(question("new", text))))
                .extracting(PlanQuestion::id).containsExactly("new");
    }

    @Test
    void shouldKeepContradictoryOrTrulyUnknownAuthorizationStatus() {
        assertThat(plan("核对两家医院共享原始记录的授权材料。", List.of(
                file("materials/hospital/说明甲.md", "跨院原始记录共享：未批准。"),
                file("materials/hospital/说明乙.md", "跨院原始记录共享：已批准。")),
                List.of(question("sharing", "跨院共享原始记录是否已获批准？"))))
                .anyMatch(question -> question.id().equals("sharing") || question.id().startsWith("context-conflict-"));
        assertThat(plan("跨院共享原始记录是否获批尚未确定。", List.of(),
                List.of(question("sharing", "跨院共享原始记录是否已获批准？")))).hasSize(1);
    }

    @Test
    void shouldStopReinsertingResolvedVersionStatusButRetainEveryIndependentRestriction() {
        String background = "materials/legal/审批现行说明.md：科研标准中心审批金额 > 50000元时二级复核；该规则是否在本次继续采用尚待负责人确认。";
        var answers = List.of(new PlanAnswer("context-conflict-1", CONFLICT, SELECTED),
                new PlanAnswer("refund", "退款审核标准是什么？", "退款审核标准尚未确定，不能套用审批门槛。"));
        var result = assemble(RAW, background, "当前尚未确认采用哪版，不自行折中。材料均未签署，不代表已生效合同。", answers);
        assertThat(result.optimizedPrompt()).doesNotContain("当前尚未确认采用哪版", "该规则是否在本次继续采用尚待负责人确认")
                .contains("不自行折中", "退款审核标准", "未签署", "不代表已生效合同", "金额严格大于50000元");
        assertThat(result.ambiguities()).singleElement().asString().contains("退款审核标准");
        assertThat(answers.getFirst().answer()).isEqualTo(SELECTED);
    }

    @Test
    void shouldKeepPendingVersionAndNewEffectiveDateAfterPartialConfirmation() {
        var result = assemble(RAW, "整理材料。", "退款审核标准尚未提供；新版生效日期尚未确定。",
                List.of(new PlanAnswer("context-conflict-1", CONFLICT, SELECTED + "生效日期尚未确定，实施前需确认。")));
        assertThat(result.optimizedPrompt()).contains("生效日期尚未确定", "退款审核标准尚未提供")
                .doesNotContain("当前尚未确认采用哪版");
        assertThat(assemble(RAW, "整理材料。", "不自行折中。",
                List.of(new PlanAnswer("context-conflict-1", CONFLICT, "暂不确定，不自行折中。"))).optimizedPrompt())
                .contains("尚未确认采用哪版");
    }

    @Test
    void shouldNotTreatANewSourceVersionAsResolvedByAnOldSelection() {
        var result = assemble(RAW, "新意见审批金额 > 80000元时二级复核，当前尚未确认采用哪版。", "不自行折中。",
                List.of(new PlanAnswer("context-conflict-1", CONFLICT, SELECTED)));
        assertThat(section(result, PromptSectionType.BACKGROUND)).contains("80000", "尚未确认采用哪版");
    }

    @Test
    void shouldMergeTheCapturedRemainingStateExplanationWithoutDroppingIt() {
        var answers = List.of(new PlanAnswer("status", "乙医院的状态取值有哪些？分别对应什么业务含义？",
                "乙医院仅确认BOOKED表示预约、CANCELLED表示取消且不计到诊、ATTENDED表示到诊；其余状态及映射暂不确定，不套用甲医院的完整状态清单。"));
        String reminder = "乙医院除 BOOKED、CANCELLED、ATTENDED 之外的状态取值及映射尚未提供，无法确定其到诊口径；在映射明确前不得计算跨院一致的到诊率。";
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(List.of(reminder), List.of(), List.of());
        assertThat(result.executionPrerequisites()).singleElement().asString().contains("不得计算跨院一致的到诊率");
    }

    @Test
    void shouldMergeTheCapturedColumnMeaningReminderWithoutDroppingItsImpact() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("columns", "乙医院预约登记包含哪些列名？",
                "乙医院字段名及业务含义暂不确定，请保留待收集信息表，不编造实际字段。")));
        var merged = new PlanAmbiguityMerger(decisions).merge(List.of(
                "乙医院预约登记包含哪些列名及其业务含义尚未确定；请保留待收集信息表，不编造实际字段；该缺口影响字段对照表与检查清单中乙医院部分的完整性。"), List.of(), List.of());
        assertThat(merged.executionPrerequisites()).singleElement().asString().contains("检查清单中乙医院部分的完整性");
    }

    @ParameterizedTest
    @ValueSource(strings = {"丙医院预约登记", "乙医院2026年预约登记", "乙医院退款登记"})
    void shouldKeepNewSubjectsWhenTheReminderUsesTheSameColumnMeaningGrammar(String subject) {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("columns", "乙医院预约登记包含哪些列名？",
                "乙医院字段名及业务含义暂不确定，不编造实际字段。")));
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(subject + "包含哪些列名及其业务含义尚未确定；请补充真实字典。"),
                List.of(), List.of()).executionPrerequisites()).hasSize(2);
    }

    @Test
    void shouldKeepTheCapturedFuturePermissionImpactAsAKnownConstraint() {
        assertThat(new PlanFindingClassifier().classify("跨院共享原始记录尚未获批准，当前各院分别处理、仅交付不可识别的统计结构；若后续授权状态变化，将影响整理步骤与交付范围。",
                List.of("跨院共享原始记录尚未获批准。各院分别处理，仅交付不可识别的统计结构。")))
                .isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"丙医院", "乙医院除 ATTENDED_EXTRA 之外", "乙医院在2026年除 BOOKED 之外"})
    void shouldKeepANewObjectCodeOrPeriodInRemainingStateExplanation(String prefix) {
        var answers = List.of(new PlanAnswer("status", "乙医院的状态取值有哪些？分别对应什么业务含义？",
                "乙医院仅确认BOOKED表示预约、CANCELLED表示取消、ATTENDED表示到诊；其余状态及映射暂不确定。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of(prefix + "的状态取值及映射尚未提供，需核对真实口径。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2).anyMatch(value -> value.contains(prefix));
    }

    @Test
    void shouldMoveTheVerifiedUnapprovedSharingReminderToConstraints() {
        String raw = "跨院共享原始记录尚未获批准。各院分别处理，仅交付不可识别的统计结构。";
        String reminder = "跨院共享原始记录尚未获批准，当前只能各院分别处理并交付不可识别的统计结构；若后续需要跨院合并原始记录，须先取得授权。";
        assertThat(new PlanFindingClassifier().classify(reminder, List.of(raw))).isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", raw),
                new PromptSection(PromptSectionType.TASK, "任务", "整理合成行政资料。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付整理步骤。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不能凭技术可合并认定已授权。")), "test", "fixture", false, List.of(reminder));
        var result = new OptimizationResultAssembler().assemble(response, context(List.of()),
                new PromptTemplate(TemplateCode.GENERAL, "交付结果", "符合要求", "示例"),
                List.of(), List.of(), false, List.of("不得泄露凭据"), false, 1, raw);
        assertThat(result.ambiguities()).isEmpty();
        assertThat(section(result, PromptSectionType.CONSTRAINTS)).contains(reminder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"跨院共享原始记录尚未获批准，丙医院新增共享范围尚未确定。", "跨院共享原始记录尚未获批准，授权到期日期尚未提供。", "跨院共享原始记录是否已获批准？"})
    void shouldNotMoveAnAdditionalAuthorizationDecisionOutOfPending(String reminder) {
        assertThat(new PlanFindingClassifier().classify(reminder, List.of("跨院共享原始记录尚未获批准。")))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"跨院共享原始记录尚未获批准，若后续需要合并原始记录，须先获得授权确认；当前仅交付各院分别处理的不可识别统计结构。", "跨院共享原始记录尚未获批准。", "跨院共享原始记录尚未获批准，当前仅交付各院分别处理的不可识别统计结构；若后续需要共享原始记录，必须先取得授权。"})
    void shouldClassifyTheCurrentRestrictionWithoutReaskingItsFuturePermissionPrecondition(String reminder) {
        assertThat(new PlanFindingClassifier().classify(reminder, List.of(
                "跨院共享原始记录尚未获批准。各院分别处理，仅交付不可识别的统计结构。")))
                .isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
    }

    @Test
    void shouldNotGeneralizeOneNamedHospitalsAuthorizationToAllHospitals() {
        assertThat(plan("核对甲乙医院原始记录共享的授权状态。", List.of(
                file("materials/hospital/甲医院授权.md", "甲医院跨院共享原始记录：未批准。乙医院授权资料尚未提供。")),
                List.of(question("sharing", "跨院共享原始记录是否已获批准？"))))
                .extracting(PlanQuestion::id).containsExactly("sharing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"假设乙医院状态名称与甲医院相同，直接按同名映射。", "同名字段直接视为同义进行对照。", "就诊日期缺失时，用创建日期代替就诊日期进行区间判断。"})
    void shouldRejectTheCapturedReopenedMappingChoices(String choice) {
        var guard = new RequirementFidelityGuard();
        var rules = guard.explicitRules("同名字段只在明确映射后对照，不能把同名当成同义。不能把创建日期当作就诊日期。", List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.validate(choice, rules, "questions.options.answer"))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"同名字段先并列展示，在明确映射关系后才能对照。", "不得将创建日期代替就诊日期。", "创建日期用于登记检查，就诊日期用于区间判断。"})
    void shouldKeepLegalMappingChecksAndSeparateDateUses(String choice) {
        var guard = new RequirementFidelityGuard();
        guard.validate(choice, guard.explicitRules("不能把同名当成同义。不能把创建日期当作就诊日期。", List.of()), "sections");
    }

    @Test
    void shouldDelegateTheCapturedOrganizationQuestionButKeepItsNewMetricDecision() {
        String raw = "只交整理步骤。必须分别列出已知事实、冲突材料与未决选择。常规章节组织、表格列宽和配色由执行者处理，无需让我决定。";
        var presentation = new PlanQuestion("presentation", "你希望最终提示词在交付物中如何组织“已知事实、冲突材料与未决选择”这三部分？", "分别列出三部分。",
                PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("a", "三部分独立列出", "三部分独立成节", "已知事实、冲突材料与未决选择分别独立成节列出。", false),
                new PlanOption("b", "一张分类表", "用类型列区分", "将已知事实、冲突材料与未决选择合并为一张表，用类型列区分。", false)), List.of(), true);
        assertThat(plan(raw, List.of(), List.of(presentation))).isEmpty();
        var newMetric = new PlanQuestion("metric", presentation.question(), "另外需确认新增指标阈值。",
                presentation.type(), presentation.options(), List.of(), true);
        assertThat(plan(raw, List.of(), List.of(newMetric))).extracting(PlanQuestion::id).containsExactly("metric");
    }

    @Test
    void shouldLeaveDirectEnhancementAndUnboundUnknownStatusUntouched() {
        var result = assemble(RAW, "整理材料。", "当前尚未确认采用哪版，不自行折中。", List.of());
        assertThat(result.optimizedPrompt()).contains("尚未确认采用哪版", "不自行折中");
    }

    private List<PlanQuestion> plan(String raw, List<FileSnippet> files, List<PlanQuestion> questions) {
        var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(CLOCK),
                request -> context(files), new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        var prepared = sessions.prepareContext(new PlanningContextRequest(raw, new ContextAnalysisRequest("", files.stream()
                .map(file -> new ContextFileInput(file.path(), file.content(), file.language())).toList()), PermissionPolicyInput.empty()));
        var service = new OptimizationPlanningServiceImpl(request -> new PlanningProviderResponse("只确认缺口", questions,
                "mock", "fixture", true), new PromptTemplateRegistryImpl(), sessions, CLOCK);
        return service.plan(new OptimizationPlanRequest(raw, "", List.of(),
                new PlanningContextReference(prepared.contextId(), prepared.version()))).questions();
    }

    private PlanQuestion question(String id, String text) {
        return new PlanQuestion(id, text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }

    private FileSnippet file(String path, String text) { return new FileSnippet(path, "markdown", text, text, false); }

    private ContextSnapshot context(List<FileSnippet> files) {
        return new ContextSnapshot("", List.of(), List.of(), List.of(), files, List.of(), List.of(), "fixture-v1");
    }

    private OptimizationResult assemble(String raw, String background, String constraints, List<PlanAnswer> answers) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", background),
                new PromptSection(PromptSectionType.TASK, "任务", "按已确认选择整理资料。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付中文核查方案。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints)), "test", "fixture", false, List.of());
        return new OptimizationResultAssembler().assemble(response, context(List.of()),
                new PromptTemplate(TemplateCode.GENERAL, "交付结果", "符合已确认要求", "示例"),
                List.of(), answers, !answers.isEmpty(), List.of("不得泄露凭据"), false, 1, raw);
    }

    private String section(OptimizationResult result, PromptSectionType type) {
        return result.sections().stream().filter(section -> section.type() == type).findFirst().orElseThrow().content();
    }
}
