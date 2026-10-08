package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本次窗口值前置的自然语言确认只能绑定同一机构、年份和取值，不扩大为阈值或批准状态。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PrefixedWindowConfirmationTest {
    private static final String RAW = "甲院2025年观察窗口尚未确定。甲院2025年异常等待阈值尚未确定。"
            + "乙院2025年观察窗口尚未确定。甲院2026年观察窗口尚未确定。";

    @Test
    void anExplicitPrefixedWindowValueRegistersOnlyItsOwnInstitutionAndYear() {
        var contract = contract("本次仅确认甲院2025年的24小时观察窗口；48小时仍是未批准候选，异常等待阈值仍需独立核实。");
        assertThat(contract.independentEvidenceGuidance()).contains("| 甲院2025年的观察窗口 | 24小时 |")
                .doesNotContain("| 甲院2025年的观察窗口 | 待确认 |");
        assertThat(contract.pendingStatements()).contains("甲院2025年异常等待的阈值尚未确定。",
                "乙院2025年的观察窗口尚未确定。", "甲院2026年的观察窗口尚未确定。");
    }

    @Test
    void theSameConfirmedValueBeforeOrAfterTheWindowNameIsAllowedInNarrative() {
        var contract = contract("本次仅确认甲院2025年的24小时观察窗口。");
        contract.validate("用户已确认甲院2025年的24小时观察窗口。", "sections.BACKGROUND");
        contract.validate("甲院2025年的24小时观察窗口已由用户确认。", "sections.CONSTRAINTS");
        contract.validate("用户已确认甲院2025年的观察窗口。", "sections.TASK");
    }

    @Test
    void theActualNonInferenceAnswerDoesNotAssertConfirmationOfAnotherParameter() {
        String actual = "本次比较观察方案沿用甲院2025年已批准并生效的24小时观察窗口。"
                + "本次仅确认甲院2025年的24小时观察窗口；48小时仍可作为比较候选说明取舍，但未获批，也不确认异常等待阈值。";
        var contract = contract(actual);
        assertThat(contract.independentEvidenceGuidance()).contains("| 甲院2025年的观察窗口 | 24小时 |");
        for (String statement : List.of(actual, "已确认的24小时窗口不代表阈值已确认。",
                "已确认的24小时窗口不代表甲院2025年异常等待阈值已确认。",
                "确认观察窗口不等于甲院2025年异常等待阈值已确认。")) {
            contract.validate(statement, "sections.CONSTRAINTS");
        }
        for (String statement : List.of("已确认的24小时窗口不代表阈值已确认，但用户已确认甲院2025年异常等待阈值。",
                "已确认的24小时窗口不代表阈值已确认但乙院2025年观察窗口已确认。",
                "已确认的48小时窗口不代表阈值已确认。")) {
            assertThatThrownBy(() -> contract.validate(statement, "sections.CONSTRAINTS")).as(statement)
                    .isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void fullActualPlanAnswersKeepTheirIndependentWindowAndThresholdStates() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var source = getClass().getResourceAsStream("/enhancement/approved-window-confirmation-actual.json")) {
            var fixture = mapper.readTree(source);
            var material = mapper.convertValue(fixture.get("material"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            for (var model : fixture.get("models")) {
                var answers = mapper.convertValue(model.get("answers"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<PlanAnswer>>() { });
                var contract = UnresolvedDecisionContract.from(fixture.get("rawPrompt").asText(),
                        ConfirmedDecisionSet.from(answers), material);
                assertThat(contract.independentEvidenceGuidance()).as(model.get("model").asText())
                        .contains("| 甲院2025年的观察窗口 | 24小时 |");
                answers.forEach(answer -> contract.validate(answer.answer(), "sections.CONSTRAINTS"));
                contract.validate("用户已确认甲院2025年的24小时观察窗口。", "sections.BACKGROUND");
                assertThatThrownBy(() -> contract.validate("用户已确认甲院2025年异常等待阈值。", "sections.TASK"))
                        .isInstanceOf(ProviderResponseValidationException.class);
            }
        }
    }

    @Test
    void anotherWindowValueInstitutionYearOrThresholdCannotBorrowThatConfirmation() {
        var contract = contract("本次仅确认甲院2025年的24小时观察窗口。");
        for (String claim : List.of("用户已确认甲院2025年的48小时观察窗口。",
                "用户已确认乙院2025年的24小时观察窗口。", "用户已确认甲院2026年的24小时观察窗口。",
                "用户已确认甲院2025年异常等待阈值。")) {
            assertThatThrownBy(() -> contract.validate(claim, "sections.TASK")).as(claim)
                    .isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void aFutureSuggestionUnapprovedCandidateQuotationOrExampleDoesNotEstablishAChoice() {
        for (String answer : List.of("如果以后确认甲院2025年的24小时观察窗口，再调整方案。",
                "本次尚未确认甲院2025年的24小时观察窗口。", "本次不确认甲院2025年的24小时观察窗口。",
                "建议本次仅确认甲院2025年的24小时观察窗口。", "本次仅确认甲院2025年的候选24小时观察窗口。",
                "“本次仅确认甲院2025年的24小时观察窗口”。", "> 本次仅确认甲院2025年的24小时观察窗口。",
                "~~~text\n本次仅确认甲院2025年的24小时观察窗口。\n~~~")) {
            assertThat(contract(answer).pendingStatements()).as(answer)
                    .contains("甲院2025年的观察窗口尚未确定。");
        }
    }

    private static UnresolvedDecisionContract contract(String answer) {
        return UnresolvedDecisionContract.from(RAW, ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("window", "本次比较观察方案中，观察窗口应如何设定？", answer))), List.of());
    }
}
