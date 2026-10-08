package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 窗口时长不自动确认起止事件、采集日期或独立指标分母；建议与当前执行参数分别核对。
 * 使用共享结果组装与同对象正反例，避免医院边界校验影响其他任务。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ProfessionalParameterScopeTest {
    private static final String RAW = "为甲院2025年提供观察方案，交付指标表。甲院2025年观察窗口采用24小时。"
            + "甲院2025年观察起点尚未提供；甲院2025年观察终点尚未确认；甲院2025年观察日期范围尚未确定。"
            + "异常等待率分母尚未确定。未提供的起止事件保持未知，候选定义只能列为建议。";

    @Test
    void independentlyRegistersMissingStartEndAndCalendarScope() {
        var contract = contract(RAW, List.of());
        assertThat(contract.pendingStatements()).contains("甲院2025年的观察起点尚未确定。",
                "甲院2025年的观察终点尚未确定。", "甲院2025年的观察日期范围尚未确定。",
                "异常等待率的分母尚未确定。");
        assertThat(contract.deliveryGuidance()).contains("24小时", "起止", "建议");
    }

    @ParameterizedTest
    @ValueSource(strings = {"甲院2025年观察起点采用患者挂号时刻。",
            "甲院2025年观察终点定义为患者离院时刻。",
            "甲院2025年观察日期范围为2025年1月1日至12月31日。",
            "用户已确认甲院2025年观察起点。", "用户已确认甲院2025年观察终点。"})
    void rejectsUnprovidedAnchorsAndFalseConfirmations(String invalid) {
        assertThatThrownBy(() -> assemble(invalid, List.of(), false, RAW))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void theSameUnknownRemainsUnknownInTheIndicatorTable() {
        assertThatThrownBy(() -> assemble("| 指标 | 观察起点 |\n| --- | --- |\n"
                + "| 甲院2025年 | 患者挂号时刻 |", List.of(), false, RAW))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatCode(() -> assemble("| 指标 | 观察起点 |\n| --- | --- |\n"
                + "| 甲院2025年 | 待确认 |", List.of(), false, RAW)).doesNotThrowAnyException();
    }

    @Test
    void actualStartConfirmationDoesNotConfirmTheEndOrDenominator() {
        var answers = List.of(new PlanAnswer("start", "甲院2025年观察起点采用什么事件？",
                "甲院2025年观察起点采用患者登记时刻。"));
        var contract = contract(RAW, answers);
        assertThat(contract.pendingStatements()).doesNotContain("甲院2025年的观察起点尚未确定。")
                .contains("甲院2025年的观察终点尚未确定。", "异常等待率的分母尚未确定。");
        assertThatCode(() -> assemble("甲院2025年观察起点采用患者登记时刻。", answers, true, RAW))
                .doesNotThrowAnyException();
    }

    @Test
    void hypotheticalAnswersDoNotCreateCurrentAnchors() {
        var answers = List.of(new PlanAnswer("start", "甲院2025年观察起点采用什么事件？",
                "如果以后确认甲院2025年观察起点采用患者登记时刻，再执行。"));
        assertThat(contract(RAW, answers).pendingStatements()).contains("甲院2025年的观察起点尚未确定。");
        assertThatThrownBy(() -> assemble("用户已确认甲院2025年观察起点。", answers, true, RAW))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void explicitSuggestionsRemainProposalsAndOtherScopesRemainIndependent() {
        assertThatCode(() -> assemble("建议甲院2025年观察起点采用患者登记时刻，须另行确认后才执行。"
                + "乙院2026年观察起点采用就诊开始时刻。", List.of(), false, RAW)).doesNotThrowAnyException();
        assertThat(contract("翻译以下说明，只输出译文。", List.of()).deliveryGuidance())
                .doesNotContain("观察起止", "窗口时长");
    }

    private static UnresolvedDecisionContract contract(String raw, List<PlanAnswer> answers) {
        return UnresolvedDecisionContract.from(raw, ConfirmedDecisionSet.from(answers));
    }

    private static com.promptoptimizer.enhancement.domain.OptimizationResult assemble(
            String output, List<PlanAnswer> answers, boolean plan, String raw) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "核对当前机构的观察方案。"),
                new PromptSection(PromptSectionType.TASK, "任务", "拟定有依据的方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不计算真实患者结果。")),
                "mock", "scope-test", true, List.of());
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.GENERAL, "按原定形式交付。", "核对参数来源。", ""),
                List.of(), answers, plan, List.of(), false, 1, raw, List.of());
    }
}
