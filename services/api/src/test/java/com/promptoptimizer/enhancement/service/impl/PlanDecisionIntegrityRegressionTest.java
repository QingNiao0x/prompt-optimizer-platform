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
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.service.PromptPlanningProvider;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定上一轮真实反例，验证准入前提、成对冲突证据和部分回答在共享链路中的边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanDecisionIntegrityRegressionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);
    private static final String ELIGIBILITY = "匹配使用姓名与证件号两个键，并且记录必须符合当前用户所属地区条件；两个条件都满足才属于可补值候选。";
    private static final String LEFT = "审批金额 > 50000元时二级复核";
    private static final String RIGHT = "审批金额 >= 50000元时二级复核";
    private static final String CONFLICT = "资料对“科研标准中心”存在不同取值：materials/legal/审批现行说明.md（" + LEFT
            + "）与 materials/legal/审批未批草稿.md（" + RIGHT + "）。请确认本次采用哪一项。";

    @ParameterizedTest
    @ValueSource(strings = {
            "地区信息缺失或无法核验当前地区条件时，在候选列表中展示并标注地区无法核验，用户仍可选择该候选并进入补值流程。",
            "地区信息缺失或无法核验当前地区条件时，展示该记录并提示地区无法核验，由用户明确确认是否继续使用该候选。",
            "地区无法核验时不得直接补值，但用户确认后允许进入补值流程。",
            "仅凭姓名与证件号匹配即可作为可补值候选。",
            "地区无法核验时直接补值，不再保留地区限制。",
            "地区信息缺失或无法核验的候选正常展示，但提示地区无法核验，由用户自行决定是否继续确认。",
            "地区无法核验时展示该候选，用户仍可确认使用该候选。"})
    void shouldRepairEligibilityBypassEvenWithDocumentOnlyContext(String invalid) {
        AtomicInteger attempts = new AtomicInteger();
        var prepared = planning("完善地区匹配表单，无法核验地区的候选展示方式尚未确定。", List.of(
                file("materials/software/current-brief.md", ELIGIBILITY)), request -> {
            String answer = attempts.incrementAndGet() == 1 ? invalid : "无法核验地区时展示但不允许补值，核验成功且符合当前地区条件后才可使用。";
            return response(List.of(choice("region", "无法核验地区的候选应如何处理？", answer)));
        });
        var plan = prepared.service().plan(prepared.request());
        assertThat(attempts).hasValue(2);
        assertThat(plan.questions()).singleElement().satisfies(question -> assertThat(question.options())
                .noneMatch(option -> option.answer().equals(invalid)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"无法核验地区时不展示该候选，不允许补值。",
            "无法核验地区时展示并标记待核验，但禁止参与补值。",
            "无法核验地区时先补齐资料，核验成功且符合当前地区条件后才允许进入补值流程。",
            "无法核验地区时先补齐资料，核验成功且符合当前地区条件后，才允许进入补值流程。",
            "只有核验成功且符合当前地区条件时，才可选择该候选并进入补值流程。",
            "只有符合当前用户所属地区条件的记录才允许补值。",
            "在无法核验地区时，不允许用户选择该候选进行补值。",
            "地区无法核验的记录不能直接被作为可补值候选。",
            "地区无法核验时由用户自行决定是否继续确认核验资料，不执行补值。"})
    void shouldRetainSafePresentationAndSuccessfulVerificationChoices(String answer) {
        AtomicInteger attempts = new AtomicInteger();
        var prepared = planning("完善地区匹配表单，无法核验地区的候选展示方式尚未确定。", List.of(
                file("materials/software/current-brief.md", ELIGIBILITY)), request -> {
            attempts.incrementAndGet();
            return response(List.of(choice("region", "无法核验地区的候选应如何处理？", answer)));
        });
        assertThat(prepared.service().plan(prepared.request()).questions()).hasSize(1);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void shouldRejectEligibilityBypassInFinalExecutionTextAlsoInDirectMode() {
        assertThatThrownBy(() -> assemble(ELIGIBILITY, "地区无法核验时由用户确认后允许进入补值流程。", List.of(), List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void shouldAssociateRephrasedApprovalQuestionByItsCompleteRulesWithoutSuppressingRefund() {
        var files = List.of(file("materials/legal/审批现行说明.md", "科研标准中心：" + LEFT),
                file("materials/legal/审批未批草稿.md", "科研标准中心：" + RIGHT));
        var approval = new PlanQuestion("approval_threshold", "本次合同审批的二级复核金额门槛，采用哪一版本？", "",
                PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("gt", "现行说明", "", "采用现行说明：合同审批金额严格大于50000元时进入二级复核，等于50000元不复核。", false),
                new PlanOption("ge", "未批草稿", "", "采用未批草稿：合同审批金额大于或等于50000元时进入二级复核。", false)), List.of(), true);
        var refund = new PlanQuestion("refund", "退款审核标准的具体内容是什么？", "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        var prepared = planning("为科研标准中心核对合同审批与退款规则，保留严格比较符，退款标准尚未提供。", files,
                request -> response(List.of(approval, refund)));
        var questions = prepared.service().plan(prepared.request()).questions();
        assertThat(questions).extracting(PlanQuestion::id).containsExactly("context-conflict-1", "refund");
    }

    @Test
    void shouldAssociateAScalarApprovalThresholdWithoutBorrowingItForRefundOrUrgentOrders() {
        String bound = "资料对“审批金额阈值”存在不同取值：docs/现行审批.md（30000元）与 docs/候选审批方案.md（50000元）。请确认本次采用哪一项。";
        var question = new PlanQuestion("threshold_choice", "本次订单审批方案应采用哪个金额阈值？", "",
                PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("first", "采用30000元", "", "采用30000元作为审批金额阈值；仅金额严格大于30000元时才触发财务复核，等于30000元时不触发。", false),
                new PlanOption("second", "采用50000元", "", "采用50000元作为审批金额阈值；仅金额严格大于50000元时才触发财务复核，等于50000元时不触发。", false)), List.of(), true);
        var identity = PlanningConflictIdentity.parse(bound).orElseThrow();
        assertThat(identity.matchesQuestion(question)).isTrue();
        for (String topic : List.of("退款审批方案", "紧急订单审批方案")) {
            var separate = new PlanQuestion(question.id(), question.question().replace("订单审批方案", topic),
                    question.hint(), question.type(), question.options(), question.examples(), question.allowCustomAnswer());
            assertThat(identity.matchesQuestion(separate)).isFalse();
        }
    }

    @Test
    void shouldResolveTheChosenRuleWithItsOperatorAndPreserveNewEvidence() {
        var answers = List.of(new PlanAnswer("context-conflict-1", CONFLICT,
                "本次采用科研标准中心现行审批说明：金额严格大于50000元才二级复核；等于50000元不复核；未批准的>=50000元草稿不作为本次执行规则。"));
        var files = new ArrayList<>(List.of(file("materials/legal/审批现行说明.md", "科研标准中心：" + LEFT),
                file("materials/legal/审批未批草稿.md", "科研标准中心：" + RIGHT)));
        assertThat(new ContextConflictDetector().detect(context(files), answers, "核对科研标准中心审批规则")).isEmpty();
        files.add(file("materials/legal/二次意见.md", "科研标准中心：审批金额 > 80000元时二级复核"));
        assertThat(new ContextConflictDetector().detect(context(files), answers, "核对科研标准中心审批规则"))
                .singleElement().asString().contains(LEFT, "80000元", "二次意见.md").doesNotContain(RIGHT);
    }

    @Test
    void shouldKeepAnotherSourceEvenIfItRepeatsAnOldDiscardedValue() {
        var answer = new PlanAnswer("context-conflict-1", CONFLICT, "本次采用：" + LEFT);
        var files = List.of(file("materials/legal/审批现行说明.md", "科研标准中心：" + LEFT),
                file("materials/legal/审批未批草稿.md", "科研标准中心：" + RIGHT),
                file("materials/legal/新收到的意见.md", "科研标准中心：" + RIGHT));
        assertThat(new ContextConflictDetector().detect(context(files), List.of(answer), "核对科研标准中心审批规则"))
                .singleElement().asString().contains("新收到的意见.md", LEFT, RIGHT);
    }

    @Test
    void shouldNotConstructAConflictBetweenApprovalAndMissingRefundFactsUnderTheSameOrganization() {
        var files = List.of(file("materials/legal/现行.md", "科研标准中心：" + LEFT),
                file("materials/legal/退款.md", "科研标准中心：退款审核标准尚未提供"),
                file("materials/legal/退款现行.md", "科研标准中心：退款金额 > 10000元时二级复核"));
        assertThat(new ContextConflictDetector().detect(context(files), List.of(), "核对科研标准中心审批和退款标准")).isEmpty();
    }

    @Test
    void shouldCompareEachBusinessObjectUnderTheSameOrganizationWithItsOwnChosenValue() {
        String refund = "资料对“科研标准中心”存在不同取值：materials/legal/退款旧.md（退款金额 > 10000元时二级复核）与 materials/legal/退款新.md（退款金额 > 20000元时二级复核）。请确认本次采用哪一项。";
        var answers = List.of(new PlanAnswer("context-conflict-1", CONFLICT, "本次采用：" + LEFT),
                new PlanAnswer("context-conflict-2", refund, "本次采用：退款金额 > 20000元时二级复核"));
        var files = List.of(file("materials/legal/审批现行说明.md", "科研标准中心：" + LEFT),
                file("materials/legal/审批未批草稿.md", "科研标准中心：" + RIGHT),
                file("materials/legal/退款旧.md", "科研标准中心：退款金额 > 10000元时二级复核"),
                file("materials/legal/退款新.md", "科研标准中心：退款金额 > 20000元时二级复核"),
                file("materials/legal/退款新增.md", "科研标准中心：退款金额 > 30000元时二级复核"));
        assertThat(new ContextConflictDetector().detect(context(files), answers, "核对科研标准中心审批和退款标准"))
                .singleElement().asString().contains("退款金额 > 20000元", "退款金额 > 30000元").doesNotContain("10000元", "审批金额");
    }

    @Test
    void shouldMergeTheRealRefundRephrasingButPreserveAnAdditionalObject() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("refund", "退款审核标准应如何提供或处理？",
                "退款审核标准暂不确定，保留独立缺口，不能套用合同审批金额门槛，也不直接执行退款。")));
        String repeated = "退款审核标准完全未提供，需负责人补充后才能确定退款流程的审核口径；在此之前不得套用合同审批金额门槛，也不得执行退款。";
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(repeated), List.of(), List.of()).executionPrerequisites())
                .singleElement().asString().contains("负责人补充", "不得执行退款");
        String other = repeated.replace("退款审核标准", "科研标准中心审批审核标准");
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(other), List.of(), List.of()).executionPrerequisites())
                .hasSize(2).anyMatch(value -> value.contains("科研标准中心审批审核标准"));
    }

    @Test
    void shouldBindAHospitalStatusReminderToTheOriginalQuestionAndKeepOtherHospitals() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("hospital_b_status_mapping",
                "乙医院预约状态取值与甲医院BOOKED、CANCELLED、ATTENDED的对应关系是什么？",
                "乙医院状态映射尚未提供，不编造状态对应关系。")));
        String repeated = "乙医院预约状态取值与甲医院 BOOKED、CANCELLED、ATTENDED 的对应关系尚未提供，影响跨院状态对照与到诊口径；不得编造状态对应关系。";
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(repeated), List.of(), List.of()).executionPrerequisites())
                .singleElement().asString().contains("跨院状态对照", "不得编造状态对应关系");
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(repeated.replace("乙医院", "丙医院")), List.of(), List.of()).executionPrerequisites())
                .hasSize(2).anyMatch(value -> value.contains("丙医院"));
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(repeated.replace("ATTENDED", "ATTENDED_EXTRA")), List.of(), List.of()).executionPrerequisites())
                .hasSize(2).anyMatch(value -> value.contains("ATTENDED_EXTRA"));
    }

    @Test
    void shouldNotResolveRefundOrAnUrgentApprovalConditionWithTheOrdinaryApprovalChoice() {
        var answer = new PlanAnswer("context-conflict-1", CONFLICT, "本次采用：" + LEFT);
        var files = List.of(file("materials/legal/退款现行.md", "退款标准：审批金额 > 50000元时二级复核"),
                file("materials/legal/退款草稿.md", "退款标准：审批金额 >= 50000元时二级复核"),
                file("materials/legal/紧急现行.md", "紧急审批标准：审批金额 > 50000元时二级复核"),
                file("materials/legal/紧急草稿.md", "紧急审批标准：审批金额 >= 50000元时二级复核"));
        assertThat(new ContextConflictDetector().detect(context(files), List.of(answer), "核对退款及紧急审批标准"))
                .hasSize(2).anyMatch(finding -> finding.contains("退款标准")).anyMatch(finding -> finding.contains("紧急审批标准"));
    }

    @Test
    void shouldNotAssociateAnotherOrganizationsIdenticalNumericRules() {
        var files = List.of(file("materials/legal/审批现行说明.md", "科研标准中心：" + LEFT),
                file("materials/legal/审批未批草稿.md", "科研标准中心：" + RIGHT));
        var different = new PlanQuestion("other_owner", "商业标准中心的审批金额二级复核采用哪一版本？", "",
                PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("gt", "规则一", "", "采用商业标准中心规则：审批金额 > 50000元时二级复核", false),
                new PlanOption("ge", "规则二", "", "采用商业标准中心规则：审批金额 >= 50000元时二级复核", false)), List.of(), true);
        var prepared = planning("核对科研标准中心及商业标准中心各自的合同审批规则。", files,
                request -> response(List.of(different)));
        assertThat(prepared.service().plan(prepared.request()).questions()).extracting(PlanQuestion::id)
                .containsExactly("context-conflict-1", "other_owner");
    }

    @Test
    void shouldResolveOnlyTheChosenConflictInAPartialAnswerAndKeepTheEffectiveDateUnknown() {
        String answer = "本次采用：" + LEFT + "；生效日期尚未确定，实施前必须确认。";
        var files = List.of(file("materials/legal/审批现行说明.md", "科研标准中心：" + LEFT),
                file("materials/legal/审批未批草稿.md", "科研标准中心：" + RIGHT));
        assertThat(new ContextConflictDetector().detect(context(files), List.of(new PlanAnswer("context-conflict-1", CONFLICT, answer)),
                "核对科研标准中心审批规则")).isEmpty();
        assertThat(assemble("核对审批方案。", "按已确认选择制定方案。", List.of(),
                List.of(new PlanAnswer("context-conflict-1", CONFLICT, answer))).ambiguities())
                .singleElement().asString().contains("生效日期尚未确定").doesNotContain("存在不同取值");
    }

    @Test
    void shouldNotClearTheWholeConflictUsingAChoiceLimitedToNewContracts() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("context-conflict-1", CONFLICT,
                "本次采用：" + LEFT + "且仅针对新合同。")));
        assertThat(decisions.resolvesConflict("科研标准中心", List.of(LEFT, RIGHT))).isFalse();
    }

    @Test
    void shouldKeepOnlyTheUnknownMappingFromThePartiallyConfirmedDateAnswer() {
        String answer = "检查区间已经适用于两院，均按就诊日期左闭右开[2025-01-01,2025-02-01)；乙医院日期字段映射尚未提供，不能更换统计月份或改为创建日期。";
        var answers = List.of(new PlanAnswer("month", "检查区间2025年1月是否适用于乙医院？", answer));
        var result = assemble("比较两院预约数据。", "制定检查方案。", List.of(), answers);
        assertThat(result.ambiguities()).singleElement().asString().contains("乙医院日期字段映射尚未提供")
                .doesNotContain("是否适用于乙医院", "该问题尚未确定：检查区间");
        assertThat(result.optimizedPrompt()).contains("[2025-01-01,2025-02-01)", "不能更换统计月份或改为创建日期");
    }

    @Test
    void shouldSeparateConfirmedMissingValuesFromTheUnknownDenominator() {
        var answers = List.of(new PlanAnswer("missing", "空单元格应如何处理？",
                "空单元格保持缺失并单独标记，不补0；具体比率分母尚未确定，需等待乙医院状态与字段映射确认。"));
        var result = assemble("比较两院预约数据。", "制定检查方案。", List.of(), answers);
        assertThat(result.ambiguities()).singleElement().asString().contains("比率分母尚未确定")
                .doesNotContain("空单元格应如何处理");
        assertThat(result.optimizedPrompt()).contains("空单元格保持缺失并单独标记，不补0", "乙医院状态与字段映射确认");
    }

    @Test
    void shouldKeepEachUnknownPartAndRetainRawAnswerForFidelity() {
        String answer = "使用R；具体版本暂不确定；参考寿命表来源尚未确定。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("analysis", "采用什么分析配置？", answer)));
        assertThat(decisions.decisions().getFirst().answer()).isEqualTo(answer);
        var result = assemble("制定减寿分析方案。", "制定检查方案。", List.of(),
                List.of(new PlanAnswer("analysis", "采用什么分析配置？", answer)));
        assertThat(result.ambiguities()).hasSize(2).anyMatch(value -> value.contains("版本暂不确定"))
                .anyMatch(value -> value.contains("参考寿命表来源尚未确定"));
        assertThat(result.optimizedPrompt()).contains("使用R");
    }

    @Test
    void shouldLeaveGenuineUnknownsAndClearDirectEnhancementUnchanged() {
        String pending = "乙医院状态映射尚未提供，需要先核对。";
        assertThat(assemble("比较两院数据。", "制定检查方案。", List.of(pending), List.of()).ambiguities()).containsExactly(pending);
        assertThat(assemble("将Hello翻译为简体中文，只输出译文。", "将Hello翻译为简体中文，只输出译文。", List.of(), List.of()).ambiguities()).isEmpty();
    }

    @Test
    void shouldMergeTheRealConditionalCandidateRephrasingWithoutDroppingNewConditions() {
        String question = "当候选记录的地区信息缺失或无法核验当前用户所属地区条件时，这条候选应如何处理？";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("Q1", question,
                "暂不确定，保留为未决前提，实施前先确认；不得绕过地区匹配条件。")));
        String repeated = "候选记录的地区信息缺失或无法核验当前用户所属地区条件时，该候选应如何处理？当前尚未决定，实施前需先确认；不得绕过地区匹配条件。";
        var merged = new PlanAmbiguityMerger(decisions).merge(List.of(repeated), List.of(), List.of());
        assertThat(merged.executionPrerequisites()).singleElement().asString().contains("不得绕过地区匹配条件");
        String newCondition = repeated.replace("地区信息缺失或", "地区信息缺失超过30日或");
        assertThat(new PlanAmbiguityMerger(decisions).merge(List.of(newCondition), List.of(), List.of()).executionPrerequisites())
                .hasSize(2).anyMatch(value -> value.contains("超过30日"));
    }

    @Test
    void shouldAssociateTheSameRuleWhenOnlyOptionsNameTheSecondaryReviewEffect() {
        var identity = PlanningConflictIdentity.parse(CONFLICT).orElseThrow();
        var question = new PlanQuestion("approval", "本次合同审批金额门槛采用哪一版？", "", PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("gt", "现行", "", "采用现行说明：合同审批金额严格大于50000元时进入二级复核。", false),
                new PlanOption("ge", "草稿", "", "采用草稿：合同审批金额大于或等于50000元时进入二级复核。", false),
                new PlanOption("pending", "暂不选择", "", "本次不选定合同审批金额门槛版本，并列保留两版差异，等待负责人确认。", false)), List.of(), true);
        assertThat(identity.matchesQuestion(question)).isTrue();
    }

    private Prepared planning(String raw, List<FileSnippet> files, PromptPlanningProvider provider) {
        var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(CLOCK),
                request -> context(files), new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        var preparation = sessions.prepareContext(new PlanningContextRequest(raw, new ContextAnalysisRequest("", files.stream()
                .map(file -> new ContextFileInput(file.path(), file.content(), file.language())).toList()), PermissionPolicyInput.empty()));
        return new Prepared(new OptimizationPlanningServiceImpl(provider, new PromptTemplateRegistryImpl(), sessions, CLOCK),
                new OptimizationPlanRequest(raw, "", List.of(), new PlanningContextReference(preparation.contextId(), preparation.version())));
    }

    private record Prepared(OptimizationPlanningServiceImpl service, OptimizationPlanRequest request) { }

    private PlanningProviderResponse response(List<PlanQuestion> questions) {
        return new PlanningProviderResponse("只确认真正缺失的业务决定", questions, "mock", "fixture", true);
    }

    private PlanQuestion choice(String id, String question, String answer) {
        return new PlanQuestion(id, question, "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("choice", "候选处理", "", answer, false),
                        new PlanOption("pending", "暂不确定", "", "暂不确定", false)), List.of(), true);
    }

    private FileSnippet file(String path, String text) { return new FileSnippet(path, "markdown", text, text, false); }

    private ContextSnapshot context(List<FileSnippet> files) {
        return new ContextSnapshot("", List.of(), List.of(), List.of(), files, List.of(), List.of(), "fixture-v1");
    }

    private OptimizationResult assemble(String raw, String task, List<String> findings, List<PlanAnswer> answers) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "用户主动提供的合成验收材料。"),
                new PromptSection(PromptSectionType.TASK, "任务", task),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付用户要求的结果。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "保留明确要求，不编造事实。")), "test", "fixture", false, findings);
        return new OptimizationResultAssembler().assemble(response, context(List.of()),
                new PromptTemplate(TemplateCode.GENERAL, "交付结果", "符合已确认要求", "示例"),
                List.of(), answers, !answers.isEmpty(), List.of("不得泄露凭据"), false, 1, raw);
    }
}
