package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.PlanConfirmation;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 将只读对照任务中的真实过度追问与必须决定生效规则的执行任务分开回放。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ComparisonMaterialDecisionTest {
    private static final String RAW = "请依据方案甲和方案乙整理订单审批与退款规则的差异表，只给方案和未决条件，不执行代码或真实业务。"
            + "两个文件都适用于同一当前工作区，均未批准，不能按文件顺序或较新日期选择胜者。"
            + "订单审批与退款属于不同业务对象，分别保留完整条件及来源。最终交付规则对照表、执行前需确认清单与只读验证步骤。"
            + "订单审批金额条件保持>=，退款金额条件保持>，不得把运算符归一化成相同值。"
            + "确认后若仍不知道答案，就在复制正文中保留未知，不默认某个阈值。";
    private static final String ORDER = "资料对“订单审批标准”存在不同取值：docs/方案甲.txt（订单金额>=3000元须复核）与 docs/方案乙.txt（订单金额>=5000元须复核）。请确认本次采用哪一项。";
    private static final String REFUND = "资料对“退款标准”存在不同取值：docs/方案甲.txt（退款金额>500元须退款负责人批准）与 docs/方案乙.txt（退款金额>800元须退款负责人批准）。请确认本次采用哪一项。";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void treatsBothBoundConflictsAsComparisonMaterialRatherThanMandatorySelections() {
        var policy = ComparisonMaterialDecision.from(input(RAW));
        assertThat(policy.coveredConflictQuestion(question(ORDER, ""))).isTrue();
        assertThat(policy.coveredConflictQuestion(question(REFUND, ""))).isTrue();
    }

    @Test
    void replaysTheFirstRealQuestionsWithoutUsingTheirModelIdsAsTheFilter() throws Exception {
        var replay = mapper.readTree(getClass().getResourceAsStream("/plan-regression/comparison-material-20261005.json"));
        assertThat(replay.path("rawPrompt").asText()).isEqualTo(RAW);
        for (var data : replay.path("replays")) {
            List<PlanQuestion> questions = mapper.convertValue(data.path("questions"),
                    mapper.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class));
            var policy = ComparisonMaterialDecision.from(input(RAW));
            assertThat(questions).isNotEmpty();
            assertThat(questions).allSatisfy(question -> assertThat(
                    policy.coveredConflictQuestion(question) || policy.delegatedPresentation(question)).as(question.question()).isTrue());
        }
    }

    @Test
    void keepsAConflictWhenTheTaskMustSelectAnEffectiveRuleOrDoesNotRequireFullComparison() {
        for (String raw : List.of("请对照订单审批规则并选择本次执行的阈值。", RAW + "本次必须选定生效的订单审批规则。",
                RAW.replace("分别保留完整条件及来源", "整理相关资料"))) {
            var policy = ComparisonMaterialDecision.from(input(raw));
            assertThat(policy.coveredConflictQuestion(question(ORDER, ""))).as(raw).isFalse();
        }
    }

    @Test
    void keepsUnboundObjectsAndChangedValuesOrComparators() {
        var policy = ComparisonMaterialDecision.from(input(RAW));
        for (String conflict : List.of(ORDER.replace("订单审批标准", "运费标准"),
                ORDER.replace(">=5000", ">=6000"), ORDER.replace(">=3000", ">3000"))) {
            assertThat(policy.coveredConflictQuestion(question(conflict, ""))).as(conflict).isFalse();
        }
    }

    @Test
    void keepsNewBusinessDecisionsEvenWhenPresentedAsATableQuestion() {
        var policy = ComparisonMaterialDecision.from(input(RAW));
        for (String detail : List.of("新增订单金额>=6000元时的复核规则。", "退款审批流程由谁批准？", "请确认跨境订单的适用范围。",
                "订单审批阈值采用>3000元。", "退款规则的币种和税率如何定义？", "退款阈值采用>=3000元。",
                "订单审批阈值为>500元。", "退款阈值展示3000元。")) {
            assertThat(policy.delegatedPresentation(question("差异表应如何展示订单审批与退款阈值？", detail))).as(detail).isFalse();
        }
    }

    @Test
    void retainsOrdinaryUnknownBusinessFactsAndAnExplicitRequestToChooseTableLayout() {
        assertThat(ComparisonMaterialDecision.from(input(RAW)).delegatedPresentation(question("订单审批负责人应由谁担任？", ""))).isFalse();
        assertThat(ComparisonMaterialDecision.from(input(RAW + "请先让我选择对照表的行列布局。"))
                .delegatedPresentation(question("差异表的结构应如何组织？", ""))).isFalse();
    }

    @Test
    void avoidsMandatorySelectionWhilePreservingBoundConflictsAndBothRulesInTheFinalCopy() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-05T04:00:00Z"), ZoneOffset.UTC);
        String first = "订单审批标准：订单金额>=3000元须复核\n退款标准：退款金额>500元须退款负责人批准";
        String second = "订单审批标准：订单金额>=5000元须复核\n退款标准：退款金额>800元须退款负责人批准";
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet("docs/方案甲.txt", "text", first, "未批准的方案甲", false),
                new FileSnippet("docs/方案乙.txt", "text", second, "未批准的方案乙", false)
        ), List.of(), List.of(), "test-v1");
        var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock), request -> context,
                new ProtectedContextFilterImpl(), TestActors.currentActor(), clock);
        var inputContext = new ContextAnalysisRequest("", List.of(new ContextFileInput("docs/方案甲.txt", first, "text"),
                new ContextFileInput("docs/方案乙.txt", second, "text")));
        var preparation = sessions.prepareContext(new PlanningContextRequest(RAW, inputContext, PermissionPolicyInput.empty()));
        var reference = new PlanningContextReference(preparation.contextId(), preparation.version());
        var before = sessions.resolveForPlan(reference, RAW, "").digest();
        assertThat(before.warnings()).filteredOn(warning -> warning.startsWith("资料对“")).hasSize(2);
        var planning = new OptimizationPlanningServiceImpl(
                request -> new PlanningProviderResponse("完整保留双方进行只读对照。", List.of(), "fixture", "planner", true),
                new PromptTemplateRegistryImpl(), sessions, clock);
        var plan = planning.plan(new OptimizationPlanRequest(RAW, "", List.of(), reference));
        assertThat(plan.questions()).isEmpty();
        assertThat(sessions.resolveForPlan(reference, RAW, "").digest()).isEqualTo(before);

        // 零追问仅代表本次对照交付已明确，原始冲突及未决执行前提不能因此被删掉。
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "两份未批准的方案均需保留。"),
                new PromptSection(PromptSectionType.TASK, "任务", "整理订单审批与退款规则的差异表。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "规则对照表、未决条件与只读验证步骤。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不执行实际业务，不默认生效阈值。")
        ), "fixture", "fixture", true, List.of());
        // 使用真实应用编排产生最终 serverFindings，避免直接组装测试遗漏二次检索冲突接入。
        var orchestrator = new DefaultEnhancementOrchestrator(request -> context, new AmbiguityDetector(),
                new PromptTemplateRegistryImpl(), new ConstraintCompleterImpl(), request -> response,
                new OptimizationResultAssembler(), new ProtectedContextFilterImpl(), sessions, clock);
        var result = orchestrator.optimize(new OptimizationRequest(RAW, inputContext, null, List.of(),
                PermissionPolicyInput.empty(), new PlanConfirmation(plan.planId(), reference, List.of())));
        assertThat(result.optimizedPrompt()).contains("订单金额>=3000元须复核", "订单金额>=5000元须复核",
                "退款金额>500元须退款负责人批准", "退款金额>800元须退款负责人批准", "docs/方案甲.txt", "docs/方案乙.txt");
    }

    private static PlanningProviderRequest input(String raw) {
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(), "COMPLETE", 2, List.of(ORDER, REFUND));
        return new PlanningProviderRequest(raw, "", List.of(), digest);
    }

    private static PlanQuestion question(String text, String detail) {
        return new PlanQuestion("synthetic", text, detail, PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("a", "保留对照", "", detail.isBlank() ? "保留对照" : detail, false),
                        new PlanOption("b", "继续核验", "", "继续核验", false)), List.of(), true);
    }

}
