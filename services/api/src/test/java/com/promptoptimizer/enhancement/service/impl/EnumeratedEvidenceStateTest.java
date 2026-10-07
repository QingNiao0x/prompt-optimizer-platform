package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
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
 * 回放平铺资料摘要内旧并列未知没有随有效部分确认更新的实际反例。
 * 当前视图可更新，但引用、未来条件、机构年份范围和不同参数不能借用本次确认。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class EnumeratedEvidenceStateTest {
    private static final String JOINT = "完整性指标分母与一致性指标分母尚需分别确定";
    private static final String SOURCE = "数据字典来源：docs/病案数据字典.md；其中明确地区为示范市、数据格式为CSV、"
            + "A医院2022年覆盖度未核实、" + JOINT + "、建议阈值不代表现行规则。";

    @Test
    void updatesOnlyTheNamedConfirmedParameterInsideAFlatSummary() {
        String current = EvidenceStateGuard.reconcileConfirmedParameters(SOURCE, choices());
        assertThat(current).contains("完整性指标分母已按本次回答确认", "一致性指标分母尚未确定",
                "A医院2022年覆盖度未核实", "数据格式为CSV", "建议阈值不代表现行规则")
                .doesNotContain(JOINT);
        assertThat(SOURCE).contains(JOINT);
    }

    @Test
    void neverTransfersAGlobalChoiceIntoAnInstitutionOrYearSummary() {
        for (String prefix : List.of("乙院资料；", "A医院2022年数据字典；", "2026年新增规则；")) {
            String source = prefix + "其中明确地区为示范市、" + JOINT + "。";
            assertThat(EvidenceStateGuard.reconcileConfirmedParameters(source, choices())).isEqualTo(source);
        }
    }

    @Test
    void neverRewritesAQuotationFutureExampleOrFencedContent() {
        for (String source : List.of("引用：“" + SOURCE + "”", "如果以后确认，再核对：" + SOURCE,
                "示例：" + SOURCE, "```text\n" + SOURCE + "\n```", "~~~text\n" + SOURCE + "\n~~~")) {
            assertThat(EvidenceStateGuard.reconcileConfirmedParameters(source, choices())).isEqualTo(source);
        }
    }

    @Test
    void preservesAnotherFullyNamedBusinessScopeAndNewConditions() {
        String source = "其中明确地区为示范市、乙院完整性指标分母与乙院一致性指标分母尚需分别确定、"
                + "甲院2026年审批另行核实、退款金额>200才进入二次审批。";
        assertThat(EvidenceStateGuard.reconcileConfirmedParameters(source, choices())).isEqualTo(source);
    }

    @Test
    void doesNotUseAnUnnamedOrHypotheticalAnswerAsAnActualChoice() {
        for (String answer : List.of("采用所有有效记录。", "如果以后确认完整性指标分母采用所有有效记录。")) {
            var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("denominator", "完整性指标的分母是什么？", answer)));
            assertThat(EvidenceStateGuard.reconcileConfirmedParameters(SOURCE, decisions.decisions())).isEqualTo(SOURCE);
        }
    }

    @Test
    void doesNotReinterpretAnUnmarkedNarrativeAsAFlatFactList() {
        String source = "甲院说明地区为示范市、" + JOINT + "，尚不能决定后续分组。";
        assertThat(EvidenceStateGuard.reconcileConfirmedParameters(source, choices())).isEqualTo(source);
    }

    @Test
    void preservesStandaloneDeclarationsInsideFencesAndOriginalLineEndings() {
        for (String fence : List.of("```", "~~~", "````")) {
            String source = fence + "text\r\n" + JOINT + "。\r\n" + fence;
            assertThat(EvidenceStateGuard.reconcileConfirmedParameters(source, choices())).isEqualTo(source);
        }
    }

    @Test
    void synchronizesTheBackgroundAndCopyableResultWithoutMutatingTheSourceCard() {
        var sourceCard = new PlanningFactCard("source", PlanningFactCategory.BUSINESS_RULE,
                "docs/病案数据字典.md", SOURCE);
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", SOURCE),
                new PromptSection(PromptSectionType.TASK, "任务", "分析病案质量。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付方法与指标表。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不同指标的分母分别确认。")),
                "test", "test", false, List.of());
        var result = new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付指标表。", "核对资料。", "示例"),
                List.of(), answers(), true, List.of("不得编造数据。"), false, 1,
                "制定病案质量分析方案，完整性指标分母与一致性指标分母尚需分别确定。",
                List.of(sourceCard), List.of());
        String background = result.sections().stream().filter(section -> section.type() == PromptSectionType.BACKGROUND)
                .findFirst().orElseThrow().content();
        assertThat(background).contains("完整性指标分母已按本次回答确认", "一致性指标分母尚未确定").doesNotContain(JOINT);
        assertThat(result.optimizedPrompt()).contains(background);
        assertThat(sourceCard.evidence()).isEqualTo(SOURCE);
    }

    private static List<ConfirmedPlanDecision> choices() {
        return ConfirmedDecisionSet.from(answers()).decisions();
    }

    private static List<PlanAnswer> answers() {
        return List.of(new PlanAnswer("completeness", "缺失记录是否进入完整性指标的分母？",
                "完整性指标分母采用所有有效出院记录，包括相应字段缺失的记录。"
                        + "仅确认完整性分母，一致性指标分母仍未决定。"));
    }
}
