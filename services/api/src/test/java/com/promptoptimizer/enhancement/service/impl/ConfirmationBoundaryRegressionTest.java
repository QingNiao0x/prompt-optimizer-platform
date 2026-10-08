package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.JavaCodeDeliveryContract;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 冻结确认边界反例，区分模型原样返回与平台自行清除未决状态、吞掉新取值的行为。
 * 合成材料不含真实业务数据，调用实际决定解析、冲突检测、提醒归并和结果组装入口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ConfirmationBoundaryRegressionTest {
    private static final String LEFT = "审批金额 > 50000元时二级复核";
    private static final String RIGHT = "审批金额 >= 50000元时二级复核";
    private static final String CONFLICT = "资料对“审批阈值”存在不同取值：docs/甲院2025规则.md（" + LEFT
            + "）与 docs/甲院2025草稿.md（" + RIGHT + "）。请确认本次采用哪一项。";

    @ParameterizedTest
    @ValueSource(strings = {
            "两份材料是否同时保留，尚未确定。",
            "同时保留两份材料的方式尚未确定。",
            "两份材料以后如果确认可以同时保留，再并列写出。",
            "两份材料不能同时保留，本次仍未确定采用哪份。"
    })
    void shouldNotResolveAConflictFromAnUnresolvedParallelPresentation(String answer) {
        var answers = List.of(new PlanAnswer("context-conflict-1", CONFLICT, answer));
        var decisions = ConfirmedDecisionSet.from(answers);
        assertThat(decisions.resolvesConflict("审批阈值", List.of(LEFT, RIGHT))).isFalse();
        assertThat(decisions.resolvesConflict("审批阈值", "docs/甲院2025规则.md", LEFT,
                "docs/甲院2025草稿.md", RIGHT)).isFalse();
        assertThat(new ContextConflictDetector().detect(context(List.of(
                file("docs/甲院2025规则.md", "审批阈值：" + LEFT),
                file("docs/甲院2025草稿.md", "审批阈值：" + RIGHT))), answers, "核对审批阈值")).hasSize(1);
    }

    @Test
    void shouldRespectExplicitParallelPresentationWithoutConfirmingEitherCalculationValue() {
        String answer = "两份材料同时保留，标明各自差异，不选定计算口径；生效日期尚未确定。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("context-conflict-1", CONFLICT, answer)));
        assertThat(decisions.resolvesConflict("审批阈值", List.of(LEFT, RIGHT))).isTrue();
        assertThat(decisions.pendingDecisions()).anyMatch(value -> value.answer().contains("生效日期尚未确定"));
    }

    @Test
    void shouldKeepFutureConfirmationUnresolvedAndSeparateFromTheConfirmedMetric() {
        String answer = "甲指标阈值确定为50000元；如果以后确认乙指标分母采用全部预约记录，再计算乙指标。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("metrics", "甲乙指标各采用什么口径？", answer)));
        assertThat(decisions.knownDecisions()).singleElement().satisfies(value ->
                assertThat(value.answer()).contains("甲指标阈值确定为50000元").doesNotContain("乙指标"));
        assertThat(decisions.pendingDecisions()).singleElement().satisfies(value ->
                assertThat(value.answer()).contains("如果以后确认乙指标分母", "再计算乙指标"));
    }

    @Test
    void shouldNotRegisterAConditionalFutureChoiceAsConfirmed() {
        String answer = "如果以后确认乙指标分母采用全部预约记录，再计算乙指标。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("denominator", "乙指标的分母如何选择？", answer)));
        assertThat(decisions.knownDecisions()).isEmpty();
        assertThat(decisions.pendingDecisions()).hasSize(1);
    }

    @Test
    void shouldKeepTheFullBusinessObjectInAFutureConfirmation() {
        assertThat(PlanAnswerSemantics.pendingSubject("如果以后确认行为指标分母为全部记录，再计算。"))
                .isEqualTo("行为指标分母");
        assertThat(PlanAnswerSemantics.confirmedPart("如果以后确认行为指标分母为全部记录，再计算。"))
                .isEmpty();
    }

    @Test
    void shouldKeepAnExecutableConditionalBusinessRule() {
        String answer = "如果用户取消，保留表单原值；若已有值为0或false，不覆盖。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("cancel", "取消和已有值如何处理？", answer)));
        assertThat(decisions.knownDecisions()).singleElement().satisfies(value -> assertThat(value.answer()).isEqualTo(answer));
        assertThat(decisions.pendingDecisions()).isEmpty();
    }

    @Test
    void shouldRetainANewCaseSensitiveFieldValueDuringReminderMerging() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("field", "输出字段使用 code 还是 Code？", "暂不确定")));
        var merged = new PlanAmbiguityMerger(decisions).merge(
                List.of("输出字段使用 CODE 还是 Code？"), List.of(), List.of());
        assertThat(merged.executionPrerequisites()).hasSize(2)
                .anyMatch(value -> value.contains("code 还是 Code"))
                .anyMatch(value -> value.contains("CODE 还是 Code"));
    }

    @Test
    void shouldNotResolveAnotherInstitutionYearOrNewValueWithTheBoundAnswer() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("context-conflict-1", CONFLICT, "本次采用：" + RIGHT)));
        assertThat(decisions.resolvesConflict("审批阈值", "docs/乙院2025规则.md", LEFT,
                "docs/乙院2025草稿.md", RIGHT)).isFalse();
        assertThat(decisions.resolvesConflict("审批阈值", "docs/甲院2026规则.md", LEFT,
                "docs/甲院2026草稿.md", RIGHT)).isFalse();
        assertThat(decisions.resolvesConflict("审批阈值", "docs/甲院2025规则.md", LEFT,
                "docs/甲院2025草稿.md", "审批金额 >= 80000元时二级复核")).isFalse();
    }

    @Test
    void shouldDetectConflictingCaseSensitiveOutputFieldsInMaterials() {
        assertThat(new ContextConflictDetector().detect(context(List.of(
                file("docs/接口现行规则.md", "输出格式：字段 code"),
                file("docs/接口候选规则.md", "输出格式：字段 Code"))), List.of(), "核对输出格式和字段"))
                .singleElement().asString().contains("字段 code", "字段 Code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"甲指标阈值确定为50000元", "甲指标阈值本次已明确为50000元", "甲指标阈值已经确认采用50000元"})
    void shouldAcceptTheRealConfirmedMetricAndStillRejectAnUnconfirmedOtherMetric(String choice) {
        String raw = "请撰写指标口径核对报告。" + choice + "；乙指标分母尚未确定。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("denominator", "乙指标分母采用什么口径？",
                choice + "；如果以后确认乙指标分母采用全部预约记录，再计算乙指标。")));
        var contract = UnresolvedDecisionContract.from(raw, decisions);
        assertThatCode(() -> contract.validate("甲指标阈值已确认为50000元，可完成甲指标边界说明。乙指标分母尚未确定。", "sections.TASK"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.validate("乙指标分母已确认采用全部预约记录。", "sections.TASK"))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }

    @Test
    void shouldReplayThePreservedRealResponseWithoutRejectingItsValidMetricConfirmation() throws Exception {
        var contract = UnresolvedDecisionContract.from("甲指标阈值本次已明确为50000元；乙指标分母尚未确定。",
                ConfirmedDecisionSet.from(List.of(new PlanAnswer("denominator", "乙指标分母采用什么口径？",
                        "甲指标阈值确定为50000元；如果以后确认乙指标分母采用全部预约记录，再计算乙指标。"))));
        try (var input = getClass().getResourceAsStream("/fixtures/confirmation-boundary/metric-confirmation-raw-pro.json")) {
            assertThat(input).isNotNull();
            var response = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            for (var section : response.path("sections")) {
                assertThatCode(() -> contract.validate(section.path("content").asText(), "sections." + section.path("type").asText()))
                        .as("真实原文的%s段", section.path("type").asText()).doesNotThrowAnyException();
            }
        }
        assertThatThrownBy(() -> contract.validate("明确说明乙指标分母已确认采用全部预约记录。", "sections.TASK"))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
        assertThatThrownBy(() -> contract.validate("明确说明甲院2026年甲指标阈值已确认。", "sections.TASK"))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }

    @Test
    void shouldKeepReportDeliveryWhenOnlyCodeIsDeclined() {
        String raw = "请撰写2015-2025年某地区心脑血管疾病死亡率特征分析报告，提供指标表，不需要代码。";
        var answers = List.of(new PlanAnswer("code", "本次是否需要附上代码？", "不需要代码，仍须撰写完整报告并交付指标表。"));
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "用户需要撰写科研报告。"),
                new PromptSection(PromptSectionType.TASK, "任务", raw),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "撰写报告正文，并附指标表。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不编造数据，不需要代码。")),
                "fixture", "frozen-response", false, List.of());
        var result = new OptimizationResultAssembler().assemble(response, context(List.of()),
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers,
                true, List.of(), false, 1, raw);
        assertThat(result.optimizedPrompt()).contains("报告正文", "指标表", "不需要代码")
                .doesNotContain("仅交付分析方案", "只交付方案", "实现文件与测试文件");
        assertThat(JavaCodeDeliveryContract.guidance(raw)).isEmpty();
    }

    private FileSnippet file(String path, String text) {
        return new FileSnippet(path, "markdown", text, text, false);
    }

    private ContextSnapshot context(List<FileSnippet> files) {
        return new ContextSnapshot("", List.of(), List.of(), List.of(), files, List.of(), List.of(), "frozen-fixture-v1");
    }
}
