package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未决参数的完整性交付保护不应替用户新增指标表、计算或伪代码任务。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RequestedDecisionDeliveryTest {
    private static final String RAW = "为甲院2025年的门诊运营组拟定比较观察方案，只交付方案，不计算真实患者数据。"
            + "甲院2025年异常等待阈值尚未确定。最终交付观察范围、两个方案的主要取舍、独立未决条件及验收。";

    @Test
    void aProposalWithAnUnknownThresholdDoesNotAcquireAnIndicatorTableOrPseudocode() {
        String guidance = contract(RAW).deliveryGuidance();
        assertThat(guidance).contains("甲院2025年异常等待", "待确认", "输入")
                .doesNotContain("仍须交付", "合入最终指标表", "用于生成指标表");
    }

    @Test
    void explicitTablesAndPseudocodeRemainIndependentRequiredDeliverables() {
        assertThat(contract(RAW + "交付指标表及清洗伪代码。").deliveryGuidance())
                .contains("原定指标表仍须交付", "原定伪代码仍须交付", "指标表逐行", "计算前提");
        assertThat(contract(RAW + "交付指标表。").deliveryGuidance())
                .contains("原定指标表仍须交付").doesNotContain("原定伪代码仍须交付");
        assertThat(contract(RAW + "交付清洗伪代码。").deliveryGuidance())
                .contains("原定伪代码仍须交付").doesNotContain("原定指标表仍须交付", "指标表逐行");
    }

    @Test
    void negativeFutureQuotationAndFenceMentionsDoNotCreateDeliverables() {
        for (String mention : List.of("不输出任何指标表，不提供伪代码。", "如果以后要求交付指标表及伪代码，再补方案。",
                "\n> 交付指标表及伪代码。", "\n“交付指标表及伪代码。”", "\n~~~text\n交付指标表及伪代码。\n~~~")) {
            assertThat(contract(RAW + mention).deliveryGuidance()).as(mention)
                    .doesNotContain("原定指标表仍须交付", "原定伪代码仍须交付", "指标表逐行", "合入最终指标表");
        }
    }

    @Test
    void copyableAssemblyPreservesUnknownParametersWithoutAddingWork() {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "甲院2025年门诊运营组的观察安排。"),
                new PromptSection(PromptSectionType.TASK, "任务", "拟定比较观察方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付观察范围、主要取舍、独立未决条件和验收。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不计算真实患者数据。")),
                "test", "test", false, List.of());
        var result = new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.GENERAL, "交付观察方案。", "核对观察边界。", "示例"),
                List.of(), List.of(), false, List.of(), false, 1, RAW, List.of());
        assertThat(result.optimizedPrompt()).contains("甲院2025年异常等待", "待确认", "独立未决条件")
                .doesNotContain("原定指标表与必要伪代码仍须交付", "合入最终指标表", "用于生成指标表");
    }

    @Test
    void originalDeliveryInputTablesStillCompactOnlyWholeIdenticalCopies() {
        String table = "当前参数依据（用于核对原定交付，不新增交付物）：\n"
                + "| 参数 | 当前口径 | 状态与依据 |\n| --- | --- | --- |\n"
                + "| 甲院2025年阈值 | 待确认 | 未决 |";
        String independent = table.replace("甲院2025年", "乙院2026年");
        var sections = new java.util.EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出",
                table + "\n" + table + "\n" + independent));
        AuthoritativeDeliveryCompactor.compact(sections, java.util.Map.of(PromptSectionType.OUTPUT, table));
        String output = sections.get(PromptSectionType.OUTPUT).content();
        assertThat(output.split(java.util.regex.Pattern.quote("| 甲院2025年阈值 |"), -1)).hasSize(2);
        assertThat(output).contains(independent);
    }

    @Test
    void independentProhibitionsDoNotEraseDeliverablesAndFutureClausesDoNotAddThem() {
        assertThat(contract(RAW + "不编造数据，交付指标表和必要伪代码，不得计算真实患者数据。").deliveryGuidance())
                .contains("原定指标表仍须交付", "原定伪代码仍须交付");
        assertThat(contract(RAW + "交付比较方案，如果以后另行确认，再交付指标表及伪代码。").deliveryGuidance())
                .doesNotContain("原定指标表仍须交付", "原定伪代码仍须交付");
    }

    private static UnresolvedDecisionContract contract(String raw) {
        return UnresolvedDecisionContract.from(raw, ConfirmedDecisionSet.from(List.of()));
    }
}
