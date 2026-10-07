package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一具名机构参数的语法改写只归入原绑定项，另一机构、年份与新条件继续保留。
 * 样例来自真实合成医院复验，不以“阈值”类别判定同一个决定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class HospitalReminderAliasRegressionTest {
    private static final String RAW = "本次分析对象为甲院和乙院的普通门诊。"
            + "两院需要独立计算后再比较，不能把两个院区的参数或待确认事项混为一项。"
            + "两院比较观察窗口尚未确定，异常等待阈值尚未确定。";

    @Test
    void mergesTheRealPossessiveThresholdReminderIntoTheSameBoundPendingItem() {
        var result = new PlanAmbiguityMerger(decisions(), RAW).merge(List.of(
                "乙院异常等待的阈值尚未确定，请提供具体分钟数，不能沿用甲院90分钟或资料中的60分钟示例，否则乙院异常比例无法计算。"),
                List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(3);
        assertThat(result.executionPrerequisites().stream().filter(value -> value.contains("乙院") && value.contains("阈值")))
                .hasSize(1).allMatch(value -> value.contains("90分钟") && value.contains("60分钟"));
    }

    @Test
    void keepsTheDifferentInstitutionsAndNewYearsAsSeparateUnknowns() {
        var result = new PlanAmbiguityMerger(decisions(), RAW).merge(List.of(
                "甲院2026年的异常等待阈值尚未确定，请提供审批依据。",
                "乙院2026年的异常等待阈值尚未确定，请提供审批依据。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(5)
                .anyMatch(value -> value.contains("甲院2026年"))
                .anyMatch(value -> value.contains("乙院2026年"));
    }

    @Test
    void retainsANewPendingApprovalConditionAfterTheSameParameterHeading() {
        var result = new PlanAmbiguityMerger(decisions(), RAW).merge(List.of(
                "乙院异常等待的阈值尚未确定；乙院2026年的审批责任人尚未确定。"), List.of(), List.of());
        assertThat(String.join("\n", result.executionPrerequisites())).contains("乙院2026年的审批责任人尚未确定");
    }

    @Test
    void doesNotTreatACommonOrUnrelatedThresholdAsTheBoundInstitutionsParameter() {
        var result = new PlanAmbiguityMerger(decisions(), RAW).merge(List.of(
                "异常等待阈值尚未确定，请提供各院适用范围。", "退款金额阈值尚未确定。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(5)
                .anyMatch(value -> value.contains("退款金额"))
                .anyMatch(value -> value.contains("各院适用范围"));
    }

    @Test
    void aliasesDoNotMakeAHypotheticalOrQuotedIndependentInstructionAuthoritative() {
        var pending = decisions().pendingDecisions().stream().filter(value -> value.question().contains("阈值")).findFirst().orElseThrow();
        for (String raw : List.of(RAW.replace("两院需要独立计算后再比较，不能把两个院区的参数或待确认事项混为一项。", ""),
                RAW.replace("两院需要独立计算后再比较，不能把两个院区的参数或待确认事项混为一项。", "如果以后确认，两院需要独立计算后再比较。"))) {
            assertThat(new PendingReminderIdentity(decisions(), raw).matches("乙院异常等待的阈值尚未确定", pending)).isFalse();
        }
    }

    @Test
    void aBareUncertainAnswerKeepsTheFullInstitutionsParameterIdentity() {
        var values = ConfirmedDecisionSet.from(List.of(new PlanAnswer("threshold-b",
                "乙院异常等待的阈值是多少？", "暂不确定")));
        var result = new PlanAmbiguityMerger(values, RAW).merge(List.of(
                "乙院的异常等待阈值尚未确定，请提供具体分钟数。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(1)
                .allMatch(value -> value.contains("乙院") && value.contains("阈值"));
    }

    @Test
    void anUnscopedReminderDoesNotCollapseWhenOnlyOneBoundItemExists() {
        var values = ConfirmedDecisionSet.from(List.of(new PlanAnswer("threshold-b",
                "乙院的异常等待阈值如何确定？", "乙院异常等待阈值尚未确定。")));
        var result = new PlanAmbiguityMerger(values, RAW).merge(List.of(
                "异常等待阈值尚未确定，请提供各院适用范围。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2)
                .anyMatch(value -> value.contains("各院适用范围"));
    }

    @Test
    void aQuotedStateIsNotTheCurrentBoundPendingStatement() {
        var pending = decisions().pendingDecisions().stream().filter(value -> value.question().contains("阈值")).findFirst().orElseThrow();
        var contract = UnresolvedDecisionContract.from(RAW, decisions(), List.of());
        assertThat(contract.matchesBoundIndependentParameter("“乙院异常等待的阈值尚未确定”", pending.question()))
                .as("bound=%s; current=%s", pending.question(), contract.pendingStatements()).contains(false);
        assertThat(new PendingReminderIdentity(decisions(), RAW)
                .matches("“乙院异常等待的阈值尚未确定”", pending)).isFalse();
    }

    @Test
    void mergesTheActualWaitingStatisticsWindowQuestionWithItsCurrentParameter() {
        var values = ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("window-a", "甲院候诊时间统计的比较观察窗口如何设定？", "甲院的观察窗口尚未决定。"),
                new PlanAnswer("window-b", "乙院候诊时间统计的比较观察窗口如何设定？", "乙院的观察窗口尚未决定。")));
        var result = new PlanAmbiguityMerger(values, RAW + "本次交付候诊时间分析方案。").merge(List.of(
                "甲院候诊时间统计的比较观察窗口尚未确定，影响该院按月统计范围；需要提供甲院独立窗口定义。",
                "乙院候诊时间统计的比较观察窗口尚未确定，影响该院按月统计范围；需要提供乙院独立窗口定义。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2)
                .allMatch(value -> value.contains("统计范围") && value.contains("独立窗口定义"));
    }

    @Test
    void waitingWindowAliasesDoNotConsumeOtherTaskOrYearOrMetricScopes() {
        var values = ConfirmedDecisionSet.from(List.of(new PlanAnswer("window-a",
                "甲院的比较观察窗口如何确定？", "甲院的观察窗口尚未决定。")));
        var contract = UnresolvedDecisionContract.from(RAW + "本次交付候诊时间分析方案。", values);
        assertThat(contract.matchesBoundIndependentParameter("甲院2026年候诊时间统计的比较观察窗口尚未确定", "甲院的比较观察窗口如何确定？"))
                .contains(false);
        assertThat(contract.matchesBoundIndependentParameter("甲院死亡率统计的比较观察窗口尚未确定", "甲院的比较观察窗口如何确定？"))
                .contains(false);
        assertThat(UnresolvedDecisionContract.from(RAW, values).matchesBoundIndependentParameter(
                "甲院候诊时间统计的比较观察窗口尚未确定", "甲院的比较观察窗口如何确定？"))
                .contains(false);
    }

    @Test
    void anActualCommonQuestionUsesTheExplicitPendingObjectInItsBoundAnswer() {
        var values = ConfirmedDecisionSet.from(List.of(new PlanAnswer("common-threshold", "异常等待的判定阈值如何确定？",
                "甲院异常等待阈值采用90分钟。乙院异常等待阈值仍未决定，不能沿用甲院取值。")));
        var result = new PlanAmbiguityMerger(values, RAW + "本次交付候诊时间分析方案。").merge(List.of(
                "乙院异常等待的判定阈值尚未确定，不能沿用甲院90分钟；该阈值直接影响乙院异常比例，需确认后方可计算。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(1)
                .allMatch(value -> value.contains("乙院") && value.contains("异常比例"));
    }

    @Test
    void anAnswerNamingTwoUnknownObjectsDoesNotProvideAUniqueBindingByCategory() {
        var values = ConfirmedDecisionSet.from(List.of(new PlanAnswer("common-threshold", "异常等待的判定阈值如何确定？",
                "甲院异常等待阈值尚未确定。乙院异常等待阈值尚未确定。")));
        var result = new PlanAmbiguityMerger(values, RAW + "本次交付候诊时间分析方案。").merge(List.of(
                "乙院异常等待的判定阈值尚未确定，需提供乙院独立取值。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2)
                .anyMatch(value -> value.contains("甲院")).anyMatch(value -> value.contains("乙院"));
    }

    @Test
    void currentWaitingThresholdAllowsVerbPositionVariantsOnlyInsideTheNamedObject() {
        var contract = UnresolvedDecisionContract.from(RAW + "本次交付候诊时间分析方案。", decisions());
        for (String subject : List.of("乙院判定异常等待", "乙院用于判定异常等待", "乙院用于标记异常等待",
                "乙院异常等待时间", "乙院异常等待的判定")) {
            assertThat(contract.matchesBoundIndependentParameter(subject + "的阈值尚未确定", "乙院异常等待的阈值是多少？"))
                    .as(subject).contains(true);
        }
    }

    @Test
    void thresholdVerbVariantsDoNotEraseAnotherMetricOrAdditionalCondition() {
        var contract = UnresolvedDecisionContract.from(RAW + "本次交付候诊时间分析方案。", decisions());
        for (String subject : List.of("乙院2026年判定异常等待", "乙院判定复诊异常等待", "丙院判定异常等待",
                "乙院仅针对儿科判定异常等待", "乙院判定异常等待审批")) {
            assertThat(contract.matchesBoundIndependentParameter(subject + "的阈值尚未确定", "乙院异常等待的阈值是多少？"))
                    .as(subject).contains(false);
        }
    }

    @Test
    void directWaitingWindowAliasesCoverTheRegisteredStateWithoutAddingShortCopies() {
        var contract = UnresolvedDecisionContract.from(RAW + "本次交付候诊时间分析方案。", ConfirmedDecisionSet.from(List.of()));
        for (String owner : List.of("甲院", "乙院")) {
            assertThat(contract.coversPendingStatement(owner + "的候诊时间比较观察窗口尚未确定，请提供该院时间范围。",
                    owner + "的观察窗口尚未确定。")).isTrue();
        }
    }

    @Test
    void directWindowCoverageDoesNotDiscardASeparateYearOrPopulation() {
        var contract = UnresolvedDecisionContract.from(RAW + "本次交付候诊时间分析方案。", ConfirmedDecisionSet.from(List.of()));
        for (String subject : List.of("甲院2026年的候诊时间比较", "甲院儿科候诊时间比较", "丙院的候诊时间比较")) {
            assertThat(contract.coversPendingStatement(subject + "观察窗口尚未确定。", "甲院的观察窗口尚未确定。"))
                    .as(subject).isFalse();
        }
    }

    @Test
    void anAliasUsedForCoverageCannotBypassTheUnconfirmedValueGuard() {
        var contract = UnresolvedDecisionContract.from(RAW + "本次交付候诊时间分析方案。", decisions());
        for (String content : List.of("乙院的候诊时间比较观察窗口采用7天。", "乙院判定异常等待的阈值采用60分钟。")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> contract.validate(content, "sections.OUTPUT"))
                    .as(content).isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
        }
    }

    private static ConfirmedDecisionSet decisions() {
        return ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("window-a", "甲院的比较观察窗口如何确定？", "甲院的观察窗口尚未决定，另一院窗口不能替代该项。"),
                new PlanAnswer("window-b", "乙院的比较观察窗口如何确定？", "乙院的观察窗口尚未决定，另一院窗口不能替代该项。"),
                new PlanAnswer("threshold-a", "甲院异常等待的阈值是多少？",
                        "甲院异常等待阈值采用90分钟。乙院异常等待阈值仍未决定，不能沿用甲院取值。"),
                new PlanAnswer("threshold-b", "乙院异常等待的阈值是多少？", "乙院异常等待阈值仍未决定，不能沿用甲院取值。")));
    }
}
