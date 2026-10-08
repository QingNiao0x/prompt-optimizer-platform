package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一机构年份的窗口选择只更新已解决的请求，未获批候选与独立参数继续原样保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ResolvedWindowChoiceTest {
    private static final String REQUEST = "本次是否继续沿用24小时窗口，还是提出48小时候选供院方审批，尚未选择，请先让我确认。";
    private static final String RAW = "为甲院2025年的门诊运营组拟定比较观察方案。" + REQUEST
            + "48小时候选不得写成已经获批。甲院2025年异常等待阈值仍需独立核实。";
    private static final String CHOSEN = "本次仅确认甲院2025年的24小时观察窗口；48小时仍可比较，但未获批，不确认阈值。";

    @Test
    void onlyTheResolvedCurrentWindowRequestIsRemovedFromEffectiveRules() {
        var decisions = decisions(CHOSEN);
        String current = ResolvedPlanState.from(decisions, RAW).reconcile(RAW);
        assertThat(current).doesNotContain("尚未选择", "请先让我确认")
                .contains("甲院2025年的24小时观察窗口", "48小时候选", "不得写成已经获批", "异常等待阈值仍需独立核实");
        assertThat(String.join("\n", new RequirementFidelityGuard().explicitRules(RAW, decisions.decisions())))
                .doesNotContain(REQUEST).contains("不得写成已经获批");
    }

    @Test
    void anotherInstitutionYearValueOrFutureAnswerCannotResolveThisRequest() {
        for (String answer : List.of("本次仅确认乙院2025年的24小时观察窗口。",
                "本次仅确认甲院2026年的24小时观察窗口。", "本次仅确认甲院2025年的48小时观察窗口。",
                "如果以后确认甲院2025年的24小时观察窗口，再调整方案。", "暂不确定。",
                "甲院2025年的24小时观察窗口已经批准，不代表本次已选择。")) {
            assertThat(ResolvedPlanState.from(decisions(answer), RAW).reconcile(RAW)).as(answer).contains(REQUEST);
        }
        assertThat(ResolvedPlanState.from(ConfirmedDecisionSet.from(List.of()), RAW).reconcile(RAW)).contains(REQUEST);
    }

    @Test
    void ambiguousScopeQuotationFenceAndNewConditionsArePreserved() {
        var decisions = decisions(CHOSEN);
        String anotherScope = RAW + "乙院2025年也需要窗口选择。";
        assertThat(ResolvedPlanState.from(decisions, anotherScope).reconcile(anotherScope)).contains(REQUEST);
        var state = ResolvedPlanState.from(decisions, RAW);
        String examples = "> " + REQUEST + "\n~~~text\n" + REQUEST + "\n~~~\n“" + REQUEST + "”";
        assertThat(state.reconcile(examples)).isEqualTo(examples);
        assertThat(state.reconcile(REQUEST + "如果以后确认48小时获批，再改变观察方案。"))
                .doesNotContain("尚未选择").contains("如果以后确认48小时获批，再改变观察方案。");
        assertThat(state.reconcile("- " + REQUEST)).startsWith("- ").doesNotContain("尚未选择");
        assertThat(state.reconcile("乙院2025年观察安排。" + REQUEST)).contains(REQUEST);
    }

    @Test
    void conflictingCurrentWindowValuesRemainUnresolved() {
        var choices = ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("first", "观察窗口？", CHOSEN),
                new PlanAnswer("second", "观察窗口？", "本次仅确认甲院2025年的48小时观察窗口。")));
        assertThat(ResolvedPlanState.from(choices, RAW).reconcile(RAW)).contains(REQUEST);
    }

    private static ConfirmedDecisionSet decisions(String answer) {
        return ConfirmedDecisionSet.from(List.of(new PlanAnswer("window", "本次观察窗口采用哪种口径？", answer)));
    }
}
