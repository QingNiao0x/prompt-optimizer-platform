package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在实际组装入口回放衍生指标误确认和表格状态改写，区分字段规则、资料口径与有效用户回答。
 * 所有资料为合成反例；不借用另一指标、机构、年份或未来条件建立当前确认。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DerivedParameterEvidenceRegressionTest {
    private static final String RAW = "制定病案质量方法方案，交付规则表、指标表及必要伪代码。"
            + "日期格式已明确为YYYY-MM-DD，完整性指标分母与一致性指标分母尚需分别确定。";
    private static final List<PlanAnswer> ANSWERS = List.of(new PlanAnswer("completeness",
            "完整性指标的统计分母应如何确定？",
            "完整性指标分母采用所有有效出院记录，包括相应字段缺失的记录。仅确认完整性分母，一致性指标分母仍未决定。"));

    @Test
    void rejectsDerivedConfirmationWithAnAnnotatedStatusAndRenamedHeader() {
        for (String status : List.of("已确认（继承完整性）", "分母已确认，按数据字典", "已由用户确认：沿用现有口径", "已确认分母")) {
            assertThatThrownBy(() -> assemble("| 指标名称 | 分母口径 | 当前状态 |\n|---|---|---|\n"
                    + "| 格式正确率 | 相应字段非缺失记录数 | " + status + " |", List.of()))
                    .as(status).isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void rejectsAConcretePendingParameterUnderADenominatorStateHeader() {
        assertThatThrownBy(() -> assemble("| 指标 | 分母定义 | 分母状态 |\n|---|---|---|\n"
                + "| 一致性指标 | 全部有效出院记录数 | 待确认后执行 |", List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void retainsConfirmedValueAndIndependentUnknownInCopyableDeliveryGuidance() {
        var contract = UnresolvedDecisionContract.from(RAW, ConfirmedDecisionSet.from(ANSWERS));
        assertThat(contract.deliveryGuidance()).contains("所有有效出院记录", "完整性指标", "一致性指标", "待确认")
                .contains("口径依据", "新增指标", "建议")
                .doesNotContain("questionId", "confirmedDecisions", "planAnswers");
    }

    @Test
    void keepsTheSelectedDenominatorInclusionConditionInTheCurrentParameterRow() {
        var contract = UnresolvedDecisionContract.from(RAW, ConfirmedDecisionSet.from(ANSWERS));
        String row = contract.deliveryGuidance().lines().filter(line -> line.startsWith("| 完整性指标的分母 |"))
                .findFirst().orElseThrow();
        assertThat(row).contains("包括相应字段缺失的记录");
    }

    @Test
    void aBoundCurrentChoiceSupersedesAnEarlierValueForOnlyTheSameParameter() {
        String raw = "完整性指标分母采用相应字段非缺失记录数。本次重新选择该口径，交付指标表。"
                + "乙院完整性指标分母采用该院全部记录数。";
        var contract = UnresolvedDecisionContract.from(raw, ConfirmedDecisionSet.from(ANSWERS));
        assertThat(contract.deliveryGuidance()).contains("所有有效出院记录", "乙院完整性指标")
                .doesNotContain("| 完整性指标的分母 | 相应字段非缺失记录数 |");
    }

    @Test
    void sourceRulesAreEvidenceButNeverManufactureUserConfirmation() {
        var contract = UnresolvedDecisionContract.from(RAW, ConfirmedDecisionSet.from(ANSWERS),
                List.of("甲院2025年格式正确率分母采用相应字段非缺失记录数。该规则已批准并生效。"));
        assertThat(contract.deliveryGuidance()).contains("甲院2025年格式正确率", "相应字段非缺失记录数", "资料明确")
                .doesNotContain("甲院2025年格式正确率的分母：用户已确认");
        assertThatThrownBy(() -> assemble("| 指标名称 | 分母 | 状态 |\n|---|---|---|\n"
                + "| 甲院2025年格式正确率 | 相应字段非缺失记录数 | 用户已确认 |",
                List.of("甲院2025年格式正确率分母采用相应字段非缺失记录数。")))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void doesNotTurnFutureOrCandidateSourceValuesIntoCurrentParameterEvidence() {
        var contract = UnresolvedDecisionContract.from(RAW, ConfirmedDecisionSet.from(ANSWERS), List.of(
                "如果以后确认甲院2026年格式正确率分母采用全部记录数，再启动该计算。",
                "建议乙院2025年格式正确率分母采用非缺失记录数，尚未批准。"));
        assertThat(contract.deliveryGuidance()).doesNotContain("甲院2026年格式正确率 |", "乙院2025年格式正确率 |");
    }

    @Test
    void onlyAnExplicitFormatIndicatorTaskReceivesTheMissingFormatDenominatorBoundary() {
        var decisions = ConfirmedDecisionSet.from(ANSWERS);
        assertThat(UnresolvedDecisionContract.from(RAW + "统计格式不合法问题。", decisions).deliveryGuidance())
                .contains("本次参数依据没有登记格式类指标的独立分母");
        assertThat(UnresolvedDecisionContract.from(RAW, decisions).deliveryGuidance())
                .doesNotContain("本次参数依据没有登记格式类指标的独立分母");
        assertThat(UnresolvedDecisionContract.from(RAW + "统计格式不合法问题。", decisions,
                List.of("格式不合法率分母采用相应字段非缺失记录数。规则已批准并生效。"))
                .deliveryGuidance()).doesNotContain("本次参数依据没有登记格式类指标的独立分母");
    }

    @Test
    void allowsIndependentUserConfirmedParametersAndKeepsOtherScopesUnknown() {
        var answers = ConfirmedDecisionSet.from(List.of(new PlanAnswer("format", "甲院2025年格式正确率分母是什么？",
                "甲院2025年格式正确率分母采用相应字段非缺失记录数。")));
        var contract = UnresolvedDecisionContract.from("乙院2025年格式正确率分母尚未确定。", answers);
        contract.validate("| 指标名称 | 分母口径 | 当前状态 |\n|---|---|---|\n"
                + "| 甲院2025年格式正确率 | 相应字段非缺失记录数 | 用户已确认 |\n"
                + "| 乙院2025年格式正确率 | 待确认 | 未决 |", "sections.OUTPUT");
        assertThatThrownBy(() -> contract.validate("| 指标名称 | 分母口径 | 当前状态 |\n|---|---|---|\n"
                + "| 乙院2025年格式正确率 | 相应字段非缺失记录数 | 已确认（同类指标） |", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    private static com.promptoptimizer.enhancement.domain.OptimizationResult assemble(String output, List<String> evidence) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "依据脱敏病案资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", "制定质量方法方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得编造数据。")), "test", "test", false, List.of());
        var facts = evidence.stream().map(value -> new PlanningFactCard("source",
                PlanningFactCategory.BUSINESS_RULE, "docs/独立口径.md", value)).toList();
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付方法方案和指标表。", "核对口径。", "示例"),
                List.of(), ANSWERS, true, List.of("不得编造数据。"), false, 1, RAW, facts, List.of());
    }
}
