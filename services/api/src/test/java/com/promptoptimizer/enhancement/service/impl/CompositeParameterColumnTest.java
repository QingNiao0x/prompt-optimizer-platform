package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台自己要求的复合参数列也须核对独立状态；计数、资料依据和另一属性不得误当已确认分母。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CompositeParameterColumnTest {
    private static final String HEADER = "| 指标 | 分子 | 分母或所需参数 | 适用记录 | 口径依据 | 状态 |\n"
            + "| --- | --- | --- | --- | --- | --- |\n";

    @Test
    void rejectsConcreteUnknownDenominatorInThePlatformsOwnColumn() {
        var contract = contract("一致性指标分母尚未确定。", List.of(), List.of());
        assertThatThrownBy(() -> contract.validate(row("一致性指标", "全部记录数", "待确认"), "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void doesNotBorrowAConfirmedDenominatorForAnotherMetric() {
        var contract = contract("一致性指标分母尚未确定。", List.of(new PlanAnswer("c", "完整性分母是什么？",
                "完整性指标分母采用所有有效出院记录。")), List.of());
        contract.validate(row("完整性指标", "所有有效出院记录", "用户已确认"), "sections.OUTPUT");
        assertThatThrownBy(() -> contract.validate(row("格式正确率", "所有有效出院记录", "用户已确认"), "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void acceptsUnknownCellsAndUnconfirmedAdvice() {
        var contract = contract("一致性指标分母尚未确定。", List.of(), List.of());
        contract.validate(row("一致性指标", "待确认", "未决"), "sections.OUTPUT");
        contract.validate(row("新增退款率", "待确认", "建议；尚未选定"), "sections.OUTPUT");
    }

    @Test
    void doesNotTurnMaterialEvidenceIntoUserConfirmation() {
        var contract = contract("交付指标表。", List.of(), List.of("甲院2025年退款率分母采用已完成订单数。"));
        contract.validate(row("甲院2025年退款率", "已完成订单数", "资料明确；不是用户确认"), "sections.OUTPUT");
        assertThatThrownBy(() -> contract.validate(row("甲院2025年退款率", "已完成订单数", "已确认"), "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void preservesCountRowsWithoutInventingDenominators() {
        var contract = contract("一致性指标分母尚未确定。", List.of(), List.of());
        contract.validate(row("各字段格式不合法记录数", "不适用（计数指标）", "已确认"), "sections.OUTPUT");
        assertThatThrownBy(() -> contract.validate(row("格式正确率", "不适用（计数指标）", "已确认"), "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void usesTheKnownThresholdPropertyWithoutReclassifyingItAsDenominator() {
        var contract = contract("甲院异常等待阈值采用90分钟。乙院异常等待阈值尚未确定。", List.of(), List.of());
        contract.validate(row("甲院异常等待", "90分钟", "用户已确认"), "sections.OUTPUT");
        assertThatThrownBy(() -> contract.validate(row("乙院异常等待", "90分钟", "待确认"), "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void anotherYearAndInstitutionCannotInheritTheChosenScope() {
        var contract = contract("甲院2025年退款率分母采用已完成订单数。", List.of(), List.of());
        for (String metric : List.of("甲院2026年退款率", "乙院2025年退款率")) {
            assertThatThrownBy(() -> contract.validate(row(metric, "已完成订单数", "用户已确认"), "sections.OUTPUT"))
                    .as(metric).isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void unrelatedTablesAndOrdinaryTextAreNotParameterRows() {
        var contract = contract("一致性指标分母尚未确定。", List.of(), List.of());
        contract.validate("| 工作事项 | 负责人 | 状态 |\n| --- | --- | --- |\n| 表格排版 | 执行者 | 已确认 |",
                "sections.OUTPUT");
    }

    private static String row(String metric, String parameter, String status) {
        return HEADER + "| " + metric + " | 按独立定义 | " + parameter + " | 对应记录 | 按资料核对 | " + status + " |";
    }

    private static UnresolvedDecisionContract contract(String raw, List<PlanAnswer> answers, List<String> evidence) {
        return UnresolvedDecisionContract.from(raw, ConfirmedDecisionSet.from(answers), evidence);
    }
}
