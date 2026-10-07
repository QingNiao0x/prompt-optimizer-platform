package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两院明确要求独立确认时，材料中的共同未决概述不能变成一个共享参数。
 * 参数展开只登记范围，不创造取值、年份或审批事实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class IndependentHospitalParameterTest {
    private static final String RAW = "本次分析对象是甲院和乙院的普通门诊。"
            + "两院需要独立计算后再比较，不能把两个院区的参数或待确认事项混为一项。"
            + "两院比较观察窗口尚未确定。异常等待阈值尚未确定。交付指标表。";
    private static final String MATERIAL = "两院比较观察窗口尚未确定，异常等待阈值尚未确定。示例60分钟不是现行规则。";

    @Test
    void registersFourIndependentlyNamedParametersWithoutInventingValues() {
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        assertThat(contract.pendingStatements()).containsExactlyInAnyOrder("甲院的观察窗口尚未确定。",
                "乙院的观察窗口尚未确定。", "甲院异常等待的阈值尚未确定。", "乙院异常等待的阈值尚未确定。");
        assertThat(contract.independentEvidenceGuidance()).doesNotContain("60分钟");
    }

    @Test
    void updatesOnlyTheActuallyChosenInstitutionsThreshold() {
        var contract = create(RAW, List.of(new PlanAnswer("a-threshold", "甲院异常等待阈值是什么？",
                "甲院异常等待阈值采用90分钟。乙院异常等待阈值仍未决定。")), List.of(MATERIAL));
        assertThat(contract.pendingStatements()).containsExactlyInAnyOrder("甲院的观察窗口尚未确定。",
                "乙院的观察窗口尚未确定。", "乙院异常等待的阈值尚未确定。");
        assertThat(contract.independentEvidenceGuidance()).contains("甲院异常等待的阈值 | 90分钟")
                .doesNotContain("乙院异常等待的阈值 | 90分钟");
    }

    @Test
    void doesNotExpandACommonParameterWithoutAnExplicitIndependentRequirement() {
        var contract = create(RAW.replace("两院需要独立计算后再比较，不能把两个院区的参数或待确认事项混为一项。", ""),
                List.of(), List.of(MATERIAL));
        assertThat(contract.pendingStatements()).doesNotContain("甲院的观察窗口尚未确定。", "乙院的观察窗口尚未确定。");
    }

    @Test
    void neverTransfersTheTwoInstitutionScopeIntoANewYearOrAnotherObject() {
        var contract = create(RAW, List.of(), List.of("甲院2026年的观察窗口尚未确定。退款金额阈值尚未确定。"));
        assertThat(contract.pendingStatements()).contains("甲院2026年的观察窗口尚未确定。", "退款金额的阈值尚未确定。")
                .doesNotContain("乙院2026年的观察窗口尚未确定。", "甲院退款金额的阈值尚未确定。");
    }

    @Test
    void doesNotInferAUniquePairWhenTheMaterialsMentionAThirdInstitution() {
        var contract = create(RAW, List.of(), List.of(MATERIAL, "丙院数据也在本次材料中，适用范围仍需核实。"));
        assertThat(contract.pendingStatements()).doesNotContain("甲院的观察窗口尚未确定。", "乙院的观察窗口尚未确定。");
    }

    @Test
    void neverUsesAHypotheticalIndependentInstructionToExpandTheScope() {
        var contract = create(RAW.replace("两院需要独立计算后再比较，不能把两个院区的参数或待确认事项混为一项。",
                "如果以后确认，两院需要独立计算后再比较。"), List.of(), List.of(MATERIAL));
        assertThat(contract.pendingStatements()).doesNotContain("甲院的观察窗口尚未确定。", "乙院的观察窗口尚未确定。");
    }

    @Test
    void quotationNegationAndFencedExamplesCannotEstablishAnIndependentInstruction() {
        for (String instruction : List.of("“两院需要独立计算后再比较”。", "两院不需要独立计算后再比较。",
                "\n~~~text\n两院需要独立计算后再比较。\n~~~\n")) {
            var contract = create(RAW.replace("两院需要独立计算后再比较，不能把两个院区的参数或待确认事项混为一项。",
                    instruction), List.of(), List.of(MATERIAL));
            assertThat(contract.pendingStatements()).as(instruction)
                    .doesNotContain("甲院的观察窗口尚未确定。", "乙院的观察窗口尚未确定。");
        }
    }

    @Test
    void rewritesOnlyCompleteSharedPendingSentencesAndRetainsNewConditions() {
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        var current = contract.independentlyScopedPending(List.of("两院比较观察窗口尚未确定。", "异常等待阈值尚未确定。",
                "异常等待阈值尚未确定，须核对2026年的审批状态。", "退款金额阈值尚未确定。"));
        assertThat(current).contains("甲院的观察窗口尚未确定。", "乙院的观察窗口尚未确定。",
                "甲院异常等待的阈值尚未确定。", "乙院异常等待的阈值尚未确定。",
                "异常等待阈值尚未确定，须核对2026年的审批状态。", "退款金额阈值尚未确定。")
                .doesNotContain("两院比较观察窗口尚未确定。", "异常等待阈值尚未确定。");
    }

    @Test
    void neverRemovesAnUnregisteredModelIssueWhenOnlyAnotherPropertyIsKnown() {
        String raw = RAW.replace("异常等待阈值尚未确定。", "");
        var contract = create(raw, List.of(), List.of());
        assertThat(contract.independentlyScopedPending(List.of("异常等待阈值尚未确定。")))
                .containsExactly("异常等待阈值尚未确定。");
    }

    @Test
    void assembledDirectResultKeepsIndependentUnknownsInBothCopyableBasisAndReminders() {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "两院普通门诊合成资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", "制定独立分析方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付指标表，不替用户选择参数。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", RAW)), "test", "test", false,
                List.of("两院比较观察窗口尚未确定。", "异常等待阈值尚未确定。"));
        var result = new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付指标表。", "核对独立范围。", "示例"),
                List.of(), List.of(), false, List.of(), false, 1, RAW,
                List.of(new PlanningFactCard("hospital-source", PlanningFactCategory.BUSINESS_RULE,
                        "docs/两院说明.txt", MATERIAL)), List.of());
        assertThat(result.ambiguities()).containsExactlyInAnyOrder("甲院的观察窗口尚未确定。", "乙院的观察窗口尚未确定。",
                "甲院异常等待的阈值尚未确定。", "乙院异常等待的阈值尚未确定。");
        assertThat(result.optimizedPrompt()).contains("| 甲院的观察窗口 | 待确认 |", "| 乙院的观察窗口 | 待确认 |",
                "| 甲院异常等待的阈值 | 待确认 |", "| 乙院异常等待的阈值 | 待确认 |");
    }

    @Test
    void independentRegistrationDoesNotAllowAnUnscopedExampleAssignment() {
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        assertThatThrownBy(() -> contract.validate("异常等待阈值采用60分钟。", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void partialConfirmationDoesNotAuthorizeACommonThresholdOrWindowTableCell() {
        var contract = create(RAW, List.of(new PlanAnswer("a-threshold", "甲院异常等待阈值是什么？",
                "甲院异常等待阈值采用90分钟。乙院异常等待阈值仍未决定。")), List.of(MATERIAL));
        contract.validate("| 指标 | 阈值 | 状态 |\n|---|---|---|\n| 甲院异常等待 | 90分钟 | 用户已确认 |", "sections.OUTPUT");
        assertThatThrownBy(() -> contract.validate("| 指标 | 阈值 | 状态 |\n|---|---|---|\n"
                + "| 异常等待 | 90分钟 | 仅示例 |", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> contract.validate("| 指标 | 观察窗口 | 状态 |\n|---|---|---|\n"
                + "| 两院比较 | 24小时 | 建议 |", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void anExplicitComparisonWindowAliasDoesNotDuplicateTheSameOwnersPendingState() {
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        assertThat(contract.coversPendingStatement("甲院的比较观察窗口尚未确定，该参数会影响纳入范围。",
                "甲院的观察窗口尚未确定。")).isTrue();
        assertThat(contract.coversPendingStatement("甲院2026年的比较观察窗口尚未确定。",
                "甲院的观察窗口尚未确定。")).isFalse();
        assertThat(contract.coversPendingStatement("退款比较观察窗口尚未确定。",
                "甲院的观察窗口尚未确定。")).isFalse();
        var unrelated = create(RAW.replace("两院需要独立计算后再比较，不能把两个院区的参数或待确认事项混为一项。", ""),
                List.of(), List.of(MATERIAL));
        assertThat(unrelated.coversPendingStatement("甲院的比较观察窗口尚未确定。",
                "甲院的观察窗口尚未确定。")).isFalse();
    }

    @Test
    void aPendingWindowAliasCannotBypassTheAssignmentBoundary() {
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        assertThatThrownBy(() -> contract.validate("甲院的比较观察窗口采用24小时。", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void theSameActuallyChosenWindowAliasIsAllowedInNarrativeAndTable() {
        var contract = create(RAW, List.of(new PlanAnswer("a-window", "甲院的比较观察窗口是什么？",
                "甲院的比较观察窗口采用24小时。乙院的观察窗口仍未决定。")), List.of(MATERIAL));
        assertThat(contract.pendingStatements()).doesNotContain("甲院的观察窗口尚未确定。");
        contract.validate("| 指标 | 观察窗口 | 状态 |\n|---|---|---|\n| 甲院的比较 | 24小时 | 用户已确认 |", "sections.OUTPUT");
        contract.validate("用户已确认甲院的比较观察窗口。", "sections.BACKGROUND");
        assertThatThrownBy(() -> contract.validate("乙院的比较观察窗口采用24小时。", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void thePossessiveThresholdNameKeepsTheSameOwnerWithoutDroppingNewScopes() {
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        assertThat(contract.coversPendingStatement("甲院的异常等待阈值尚未确定，请提供具体分钟数。",
                "甲院异常等待的阈值尚未确定。")).isTrue();
        assertThat(contract.coversPendingStatement("乙院的异常等待阈值尚未确定。",
                "甲院异常等待的阈值尚未确定。")).isFalse();
        assertThat(contract.coversPendingStatement("甲院2026年的异常等待阈值尚未确定。",
                "甲院异常等待的阈值尚未确定。")).isFalse();
        assertThatThrownBy(() -> contract.validate("甲院的异常等待阈值采用60分钟。", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void anActuallyChosenPossessiveThresholdDoesNotConfirmTheOtherOwner() {
        var contract = create(RAW, List.of(new PlanAnswer("a-threshold", "甲院的异常等待阈值是什么？",
                "甲院的异常等待阈值采用90分钟。乙院的异常等待阈值仍未决定。")), List.of(MATERIAL));
        assertThat(contract.pendingStatements()).contains("乙院异常等待的阈值尚未确定。")
                .doesNotContain("甲院异常等待的阈值尚未确定。");
        contract.validate("| 指标 | 阈值 | 状态 |\n|---|---|---|\n| 甲院的异常等待 | 90分钟 | 用户已确认 |", "sections.OUTPUT");
        assertThatThrownBy(() -> contract.validate("乙院的异常等待阈值采用90分钟。", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void aCompleteJointUnknownAlreadyCoversBothIndependentStatesWithoutLosingItsExplanation() {
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        String windows = "甲院和乙院的比较观察窗口均未确定，且两者可能不同。请分别确认起止时间。";
        String thresholds = "甲院和乙院的异常等待阈值均未确定。请分别确认，60分钟不是院规。";
        assertThat(contract.coversPendingStatement(windows, "甲院的观察窗口尚未确定。")).isTrue();
        assertThat(contract.coversPendingStatement(windows, "乙院的观察窗口尚未确定。")).isTrue();
        assertThat(contract.coversPendingStatement(thresholds, "甲院异常等待的阈值尚未确定。")).isTrue();
        assertThat(contract.coversPendingStatement(thresholds, "乙院异常等待的阈值尚未确定。")).isTrue();
        assertThat(contract.independentlyScopedPending(List.of(windows, thresholds))).containsExactly(windows, thresholds);
    }

    @Test
    void jointCoverageRequiresBothCurrentUnknownsAndCannotCoverQuotesYearsOrAnotherObject() {
        var partial = create(RAW, List.of(new PlanAnswer("a-threshold", "甲院异常等待阈值是什么？",
                "甲院异常等待阈值采用90分钟。乙院异常等待阈值仍未决定。")), List.of(MATERIAL));
        assertThat(partial.coversPendingStatement("甲院和乙院的异常等待阈值均未确定。",
                "乙院异常等待的阈值尚未确定。")).isFalse();
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        for (String finding : List.of("“甲院和乙院的比较观察窗口均未确定”。",
                "甲院和乙院的比较观察窗口均未确定，请核实2026年的新条件。", "甲院和乙院的退款阈值均未确定。")) {
            assertThat(contract.coversPendingStatement(finding, "甲院的观察窗口尚未确定。")).as(finding).isFalse();
        }
    }

    @Test
    void aCompleteJointQuestionCoversTheAlreadyNamedUnknownsWithoutAddingFourShortCopies() {
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        String windows = "甲院和乙院用于比较的观察窗口分别是什么？两院可能不同，需分别确认。";
        String thresholds = "甲院和乙院判定异常等待的阈值分别是什么？资料中的60分钟仅为示例，须分别确认。";
        assertThat(contract.coversPendingStatement(windows, "甲院的观察窗口尚未确定。")).isTrue();
        assertThat(contract.coversPendingStatement(windows, "乙院的观察窗口尚未确定。")).isTrue();
        assertThat(contract.coversPendingStatement(thresholds, "甲院异常等待的阈值尚未确定。")).isTrue();
        assertThat(contract.coversPendingStatement(thresholds, "乙院异常等待的阈值尚未确定。")).isTrue();
        assertThat(contract.independentlyScopedPending(List.of(windows, thresholds))).containsExactly(windows, thresholds);
    }

    @Test
    void jointQuestionCoverageDoesNotApplyToCommonSelectionOrAnAlreadyAnsweredOwner() {
        var contract = create(RAW, List.of(), List.of(MATERIAL));
        assertThat(contract.coversPendingStatement("甲院和乙院是否都采用90分钟作为异常等待阈值？",
                "乙院异常等待的阈值尚未确定。")).isFalse();
        var partial = create(RAW, List.of(new PlanAnswer("a-threshold", "甲院异常等待阈值是什么？",
                "甲院异常等待阈值采用90分钟。乙院异常等待阈值仍未决定。")), List.of(MATERIAL));
        assertThat(partial.coversPendingStatement("甲院和乙院判定异常等待的阈值分别是什么？",
                "乙院异常等待的阈值尚未确定。")).isFalse();
    }

    private static UnresolvedDecisionContract create(String raw, List<PlanAnswer> answers, List<String> material) {
        return UnresolvedDecisionContract.from(raw, ConfirmedDecisionSet.from(answers), material);
    }
}
