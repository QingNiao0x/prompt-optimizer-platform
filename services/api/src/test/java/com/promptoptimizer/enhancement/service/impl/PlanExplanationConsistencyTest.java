package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证过滤后摘要、复合委托问题和实际注册题集一致；模型替身不构成真实Plan验证。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanExplanationConsistencyTest {
    private static final String RESEARCH = "本次研究拟开展，尚未实施。请撰写方法说明，说明评价维度和评分方式，提出拟议设计。实际样本来源尚未确定。";

    @Test
    void removedKnownQuestionCannotRemainAnUnresolvedFactInTheSummary() {
        var known = question("region", "研究地区具体是什么？", "");
        var required = question("choice", "是否向外部合作方提供患者数据？", "这涉及新的数据授权。");
        var plan = service(new PlanningProviderResponse("当前缺少研究地区，必须先确认研究地区和数据授权。",
                List.of(known, required), "test", "planner", false), new AtomicInteger()).plan(
                new OptimizationPlanRequest("撰写研究方案。研究地区：广东省。外部共享授权尚未确定。", "", List.of()));
        assertThat(plan.questions()).extracting(PlanQuestion::id).containsExactly("choice");
        assertThat(plan.summary()).doesNotContain("缺少研究地区", "先确认研究地区");
    }

    @Test
    void twoPurePresentationHintsDoNotCreateTwoIndependentDecisions() {
        var first = question("first", "本次对外发布的受众是谁？", "请选择。 ");
        var repeated = question("second", "本次对外发布的受众是谁？", "请确认。");
        assertThat(new PlanQuestionFilter().filter(List.of(first, repeated), input("撰写发布说明，发布受众尚未确定。")))
                .containsExactly(first);
    }

    @ParameterizedTest
    @ValueSource(strings = { "甲院2026年资料仍需单独授权。", "仅向内部人员发布，是否允许？", "发布预算为100元，是否接受？",
            "以后获批才能发布。", "请确认是否排除患者身份信息。" })
    void substantiveHintConditionsNeverDisappearBehindTheSameQuestion(String hint) {
        var first = question("first", "本次对外发布的受众是谁？", "请选择。");
        var independent = question("second", first.question(), hint);
        assertThat(new PlanQuestionFilter().filter(List.of(first, independent), input("撰写发布说明，发布受众尚未确定。")))
                .containsExactly(first, independent);
    }

    @Test
    void asksOnlyTheIndependentUnresolvedPartOfAnExplicitlySeparatedFreeTextQuestion() {
        var mixed = question("design", "具体采用什么评分尺度？实际样本来源是什么？", "");
        assertThat(new PlanQuestionFilter().filter(List.of(mixed), input(RESEARCH))).singleElement().satisfies(actual -> {
            assertThat(actual.id()).isEqualTo("design");
            assertThat(actual.question()).isEqualTo("实际样本来源是什么？");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "如果甲院批准，具体采用什么评分尺度？实际样本来源是什么？",
            "引用：具体采用什么评分尺度？实际样本来源是什么？", "具体采用什么评分尺度与实际样本来源？" })
    void compoundScopeAndAmbiguousSharedGrammarStayWhole(String text) {
        var question = question("design", text, "");
        assertThat(new PlanQuestionFilter().filter(List.of(question), input(RESEARCH))).containsExactly(question);
    }

    @Test
    void userReservedMethodSelectionAndHintInformationPreventSplitting() {
        var mixed = question("design", "具体采用什么评分尺度？实际样本来源是什么？", "");
        assertThat(new PlanQuestionFilter().filter(List.of(mixed), input(RESEARCH + "评分方式由我决定。")))
                .containsExactly(mixed);
        var withHint = question("design", mixed.question(), "两项都仅针对甲院。");
        assertThat(new PlanQuestionFilter().filter(List.of(withHint), input(RESEARCH))).containsExactly(withHint);
    }

    @Test
    void contradictorySummaryUsesExistingValidationBeforeRegisteringThePlan() {
        var calls = new AtomicInteger();
        var sessions = sessions();
        var service = new OptimizationPlanningServiceImpl(request -> new PlanningProviderResponse(
                calls.incrementAndGet() == 1 ? "匹配到记录后无需用户确认，直接填充。" : "请确认本次对外发布的受众。",
                List.of(question("audience", "本次对外发布的受众是谁？", "")), "test", "planner", false),
                new PromptTemplateRegistryImpl(), sessions, Clock.systemUTC());
        var plan = service.plan(new OptimizationPlanRequest("完善表单，经用户确认后填充。还需撰写发布说明，受众尚未确定。", "", List.of()));
        assertThat(calls).hasValue(2);
        assertThat(plan.summary()).doesNotContain("无需用户确认");
    }

    private PlanningProviderRequest input(String raw) { return new PlanningProviderRequest(raw, "", List.of()); }

    private PlanQuestion question(String id, String text, String hint) {
        return new PlanQuestion(id, text, hint, PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }

    private PlanningSessionServiceImpl sessions() {
        var clock = Clock.systemUTC();
        return new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock),
                request -> new ContextSnapshot(request.customDescription(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), "test-v1"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), clock);
    }

    private OptimizationPlanningServiceImpl service(PlanningProviderResponse response, AtomicInteger calls) {
        return new OptimizationPlanningServiceImpl(request -> { calls.incrementAndGet(); return response; },
                new PromptTemplateRegistryImpl(), sessions(), Clock.systemUTC());
    }
}
