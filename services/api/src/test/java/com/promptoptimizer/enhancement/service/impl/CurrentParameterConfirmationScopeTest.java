package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实“本次仅确认”回答应只更新具名参数；引用、未来条件和另一机构的选值不得建立当前确认。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CurrentParameterConfirmationScopeTest {
    private static final String RAW = "为甲院和乙院2025年普通门诊制定比较方案。两院需要独立计算后再比较。"
            + "两院比较观察窗口尚未确定。异常等待阈值尚未确定。交付指标表。";
    private static final String MATERIAL = "两院比较观察窗口尚未确定，异常等待阈值尚未确定。60分钟仅为示例，不是院规。";

    @Test
    void actualLimitedConfirmationUpdatesOnlyTheChosenThresholdAndAllowsTheSameTableValue() {
        var contract = hospital("甲院异常等待严格超过90分钟。本次仅确认甲院异常等待阈值为90分钟，"
                + "作为本次合成方案选值，不代表医院已批准院规。暂不确定。乙院异常等待阈值仍未确定，"
                + "两院观察窗口和医院名称与代码对应关系仍需独立核实。");
        assertThat(contract.pendingStatements()).containsExactlyInAnyOrder("甲院的观察窗口尚未确定。",
                "乙院的观察窗口尚未确定。", "乙院异常等待的阈值尚未确定。");
        assertThat(contract.independentEvidenceGuidance()).contains("| 甲院异常等待的阈值 | 90分钟 |")
                .doesNotContain("| 乙院异常等待的阈值 | 90分钟 |", "| 甲院异常等待的阈值 | 待确认 |");
        contract.validate("| 指标 | 阈值 | 状态 |\n|---|---|---|\n| 甲院异常等待 | 90分钟 | 用户已确认 |", "sections.OUTPUT");
        assertThatThrownBy(() -> contract.validate("乙院异常等待阈值采用90分钟。", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void affirmativeCurrentWrappersKeepTheSameParameterIdentity() {
        for (String prefix : List.of("本次仅确认", "本次只确认", "这次只明确确认", "本次选定", "用户已确认")) {
            var contract = hospital(prefix + "甲院异常等待阈值为90分钟。乙院异常等待阈值尚未确定。");
            assertThat(contract.pendingStatements()).as(prefix).doesNotContain("甲院异常等待的阈值尚未确定。")
                    .contains("乙院异常等待的阈值尚未确定。");
        }
    }

    @Test
    void aLimitedCompletenessChoiceDoesNotConfirmConsistencyOrDerivedFormatDenominators() {
        String raw = "完整性的分母尚未确定。一致性的分母尚未确定。格式合规率的分母尚未确定。交付指标表。";
        var contract = UnresolvedDecisionContract.from(raw, ConfirmedDecisionSet.from(List.of(new PlanAnswer("denominator",
                "完整性的分母是什么？", "本次仅确认完整性的分母为全部有效出院记录，包括缺失字段记录。"
                        + "一致性的分母尚未确定。格式合规率的分母尚未确定。"))), List.of());
        assertThat(contract.pendingStatements()).containsExactlyInAnyOrder("一致性的分母尚未确定。", "格式合规率的分母尚未确定。");
        assertThat(contract.independentEvidenceGuidance()).contains("全部有效出院记录，包括缺失字段记录");
        assertThatThrownBy(() -> contract.validate("格式合规率的分母采用全部有效出院记录。", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void futureNegatedSuggestedOrUnresolvedStatementsCannotBecomeCurrentSelections() {
        for (String text : List.of("如果以后确认甲院异常等待阈值为90分钟，再调整方案。",
                "本次尚未确认甲院异常等待阈值为90分钟。", "本次不确认甲院异常等待阈值为90分钟。",
                "建议本次仅确认甲院异常等待阈值为90分钟。", "本次仅确认甲院异常等待阈值为候选90分钟。")) {
            assertThat(hospital(text).pendingStatements()).as(text).contains("甲院异常等待的阈值尚未确定。");
        }
    }

    @Test
    void quotationsAndFencedExamplesCannotSupplyCurrentParameterEvidence() {
        for (String text : List.of("“本次仅确认甲院异常等待阈值为90分钟”。", "`甲院异常等待阈值为90分钟`。",
                "> 本次仅确认甲院异常等待阈值为90分钟。", "\n```text\n甲院异常等待阈值为90分钟。\n```",
                "\n~~~text\n本次仅确认甲院异常等待阈值为90分钟。\n~~~")) {
            var contract = UnresolvedDecisionContract.from(RAW + "\n" + text, ConfirmedDecisionSet.from(List.of()), List.of(MATERIAL));
            assertThat(contract.pendingStatements()).as(text).contains("甲院异常等待的阈值尚未确定。");
        }
    }

    @Test
    void aCurrentSelectionAfterAClosedFenceIsStillRecognized() {
        var contract = hospital("```text\n甲院异常等待阈值为60分钟。\n```\n本次仅确认甲院异常等待阈值为90分钟。");
        assertThat(contract.pendingStatements()).doesNotContain("甲院异常等待的阈值尚未确定。");
        assertThat(contract.independentEvidenceGuidance()).contains("90分钟").doesNotContain("| 甲院异常等待的阈值 | 60分钟 |");
    }

    @Test
    void anotherYearOrBusinessObjectDoesNotResolveTheCurrentHospitalDecision() {
        for (String text : List.of("本次仅确认甲院2026年异常等待阈值为90分钟。", "本次仅确认退款金额阈值为90万元。")) {
            assertThat(hospital(text).pendingStatements()).as(text)
                    .contains("甲院异常等待的阈值尚未确定。", "乙院异常等待的阈值尚未确定。");
        }
    }

    @Test
    void anIndependentObservationWindowChoiceCannotResolveItsThreshold() {
        var contract = hospital("本次仅确认甲院观察窗口为24小时。甲院异常等待阈值尚未确定。");
        assertThat(contract.pendingStatements()).doesNotContain("甲院的观察窗口尚未确定。")
                .contains("甲院异常等待的阈值尚未确定。", "乙院的观察窗口尚未确定。");
    }

    private static UnresolvedDecisionContract hospital(String answer) {
        return UnresolvedDecisionContract.from(RAW, ConfirmedDecisionSet.from(List.of(new PlanAnswer("threshold",
                "甲院异常等待阈值是什么？", answer))), List.of(MATERIAL));
    }
}
