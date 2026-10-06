package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
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
 * 在最终组装入口核对资料未知、逐指标确认及可复制正文，防止只测试内部正则而遗漏共享链路。
 * 原证据、不同机构和假设分支均保留，测试不依赖真实凭据或专业默认口径。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DecisionStateScopeRegressionTest {
    private static final String RAW = "制定病案质量研究方案，保留完整性、格式异常与一致性指标表和必要伪代码。";

    @Test
    void rejectsAConcreteDenominatorDeclaredUnknownInTheUploadedMaterial() {
        assertThatThrownBy(() -> assemble(table("一致性指标", "全部有效记录数"), RAW, List.of(), false,
                List.of(fact("一致性指标分母尚未确定。"))))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void rejectsCoordinatedPendingParametersWithoutInventingOtherUnknowns() {
        assertThatThrownBy(() -> assemble(table("一致性指标", "全部有效记录数"),
                RAW + "完整性指标分母与一致性指标分母尚需分别确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void doesNotApplyCompletenessConfirmationToTheFormatMetric() {
        var answers = List.of(new PlanAnswer("completeness", "完整性指标分母采用什么口径？",
                "完整性指标分母采用全部有效出院记录。"));
        assertThatThrownBy(() -> assemble(table("格式异常指标", "全部有效出院记录数（已确认）"), RAW,
                answers, true, List.of(fact("格式异常指标分母尚需确定。"))))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void doesNotInventConfirmationForAnotherMetricEvenWithoutAnExplicitPendingDeclaration() {
        var answers = List.of(new PlanAnswer("completeness", "完整性指标分母采用什么口径？",
                "完整性指标分母采用全部有效出院记录。"));
        assertThatThrownBy(() -> assemble("| 指标 | 分母 | 状态 |\n|---|---|---|\n"
                        + "| 格式异常指标 | 全部有效记录数 | 已确认 |", RAW, answers, true, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> assemble("用户已确认格式异常指标分母采用全部有效记录数。", RAW, answers, true, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void keepsAnExplicitCurrentParameterChoiceButNotAHypotheticalOne() {
        var result = assemble("| 指标 | 分母 | 状态 |\n|---|---|---|\n| 完整性指标 | 全部有效记录数 | 已确认 |",
                RAW + "完整性指标分母采用全部有效记录数。", List.of(), false, List.of());
        assertThat(result.optimizedPrompt()).contains("全部有效记录数");
        assertThatThrownBy(() -> assemble("| 指标 | 分母 | 状态 |\n|---|---|---|\n| 完整性指标 | 全部有效记录数 | 已确认 |",
                RAW + "如果以后确认完整性指标分母采用全部有效记录数，再计算。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void updatesOnlyTheActuallyConfirmedParameterAndKeepsOriginalEvidence() {
        var answers = List.of(new PlanAnswer("completeness", "完整性指标分母采用什么口径？",
                "完整性指标分母采用全部有效出院记录。"));
        var result = assemble(table("完整性指标", "全部有效出院记录数") + "\n" + table("格式异常指标", "待确认"),
                RAW, answers, true, List.of(fact("完整性指标分母尚未确定。"), fact("格式异常指标分母尚未确定。")));
        assertThat(result.optimizedPrompt()).doesNotContain("完整性指标分母尚未确定");
        assertThat(result.optimizedPrompt()).contains("格式异常指标", "待确认");
        assertThat(result.evidenceCards()).anyMatch(card -> card.evidence().contains("完整性指标分母尚未确定"));
    }

    @Test
    void doesNotTreatUnboundAnswersAsUserConfirmation() {
        var result = assemble("交付方法与指标表。", RAW,
                List.of(new PlanAnswer("forged", "完整性指标分母采用什么口径？", "完整性指标分母采用全部记录。")), false, List.of());
        assertThat(result.optimizedPrompt()).doesNotContain("用户已确认的信息", "完整性指标分母采用全部记录");
    }

    @Test
    void doesNotLetAnotherUnknownClauseHideAnAssignedParameter() {
        assertThatThrownBy(() -> assemble("一致性指标分母为全部有效记录数，插补方法尚未决定。",
                RAW + "一致性指标分母尚未确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void validatesParameterHeaderAliasesAndTheNamedPseudoCodeAssignment() {
        assertThatThrownBy(() -> assemble("| 指标 | 分母口径 |\n|---|---|\n| 一致性指标 | 全部有效记录数 |",
                RAW + "一致性指标分母尚未确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> assemble("```text\n一致性指标分母 = 有效记录数\n```",
                RAW + "一致性指标分母尚未确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void rejectsARecordScopeDisguisedByAPendingNoteWithoutTheCountSuffix() {
        assertThatThrownBy(() -> assemble(table("一致性指标", "全部有效记录（待确认）"),
                RAW + "一致性指标分母尚未确定。", List.of(), false, List.of()))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void keepsOtherObjectsAndExplicitConditionalBranches() {
        var result = assemble(table("乙院一致性指标", "乙院已核实的适用记录数")
                        + "\n如果以后确认甲院一致性指标分母为全部有效记录，再按该口径计算。",
                RAW + "甲院一致性指标分母尚未确定。", List.of(), false, List.of());
        assertThat(result.optimizedPrompt()).contains("乙院已核实的适用记录数", "如果以后确认");
    }

    @Test
    void doesNotExposeInternalPlanProtocolInUserFacingText() {
        assertThatThrownBy(() -> assemble("请按 confirmedDecisions 和 planAnswers 执行，保留 questionId。",
                RAW, List.of(), false, List.of())).isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void preservesProtocolNamesWhenTheyAreTheExplicitDevelopmentTask() {
        var result = assemble("说明 questionId 字段的 API 契约。", "编写 questionId 字段的 API 说明。",
                List.of(), false, List.of());
        assertThat(result.optimizedPrompt()).contains("questionId");
    }

    @Test
    void keepsOnePendingStatementAcrossTheCopyableBodyAndItsMaterialView() {
        var result = assemble("交付方法与指标表。\n一致性指标分母尚未确定。", RAW,
                List.of(), false, List.of(fact("一致性指标分母尚未确定。")));
        assertThat(result.optimizedPrompt()).doesNotContain("本次尚未确定的参数：");
        assertThat(result.sections().stream().filter(s -> s.type() == PromptSectionType.BACKGROUND)
                .map(PromptSection::content).findFirst().orElseThrow()).doesNotContain("一致性指标分母尚未确定");
        assertThat(result.sections().stream().filter(s -> s.type() == PromptSectionType.OUTPUT)
                .map(PromptSection::content).findFirst().orElseThrow()).doesNotContain("一致性指标分母尚未确定");
        assertThat(result.ambiguities()).hasSize(1);
    }

    @Test
    void preservesANewConditionNextToAnAlreadyKnownPendingDeclaration() {
        var result = assemble("交付方法与指标表。\n甲院一致性指标分母尚未确定，2026年的授权需另外核实。", RAW,
                List.of(), false, List.of(fact("甲院一致性指标分母尚未确定。")));
        assertThat(result.optimizedPrompt()).contains("2026年的授权需另外核实");
    }

    @Test
    void doesNotTurnAConditionalPremiseIntoAKnownFactInThePlanHint() {
        var guard = ConditionalConfirmationGuard.prepare("若我只确认完整性指标分母包含全部有效记录，不能推断另一指标。",
                List.of("若我只确认完整性指标分母包含全部有效记录，不能推断另一指标。"), List.of());
        assertThatThrownBy(() -> guard.validate("你已说明完整性指标分母包含全部有效记录，因此可沿用。", "questions[0].hint"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void passesTheUploadedUnknownStateToTheProviderBeforeItGeneratesAnySections() {
        var captured = new java.util.ArrayList<com.promptoptimizer.provider.domain.EnhancementProviderRequest>();
        com.promptoptimizer.provider.service.PromptEnhancementProvider provider = request -> {
            captured.add(request);
            return new EnhancementProviderResponse(List.of(
                    new PromptSection(PromptSectionType.BACKGROUND, "背景", "用户提供病案质量方案。"),
                    new PromptSection(PromptSectionType.TASK, "任务", "制定研究方法。"),
                    new PromptSection(PromptSectionType.OUTPUT, "输出", "交付方法、指标表和必要伪代码。"),
                    new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得编造数据。")), "test", "test", false, List.of());
        };
        var orchestrator = new DefaultEnhancementOrchestrator(
                new com.promptoptimizer.context.service.impl.DefaultContextAnalyzer(new com.fasterxml.jackson.databind.ObjectMapper(),
                        new com.promptoptimizer.context.service.impl.BinaryContentExtractor(),
                        new com.promptoptimizer.context.service.impl.FileContentSummarizer()),
                new AmbiguityDetector(), new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl(),
                new com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl(), provider,
                com.promptoptimizer.identity.support.TestActors.currentActor(), java.time.Clock.systemUTC());
        var result = orchestrator.optimize(new com.promptoptimizer.enhancement.dto.OptimizationRequest(RAW,
                new com.promptoptimizer.context.dto.ContextAnalysisRequest("", List.of(new com.promptoptimizer.context.dto.ContextFileInput(
                        "docs/病案质量方案.md", "完整性指标分母与一致性指标分母尚需分别确定。", "markdown"))),
                new com.promptoptimizer.enhancement.dto.EnhancementOptions(TemplateCode.AUTO, false, true, false),
                List.of(), new com.promptoptimizer.enhancement.dto.PermissionPolicyInput(List.of(), List.of())));
        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().template().outputGuidance()).contains("完整性指标的分母：待确认", "跨字段一致性指标的分母：待确认");
        assertThat(result.ambiguities()).anyMatch(value -> value.contains("一致性指标"));
    }

    private static PlanningFactCard fact(String evidence) {
        return new PlanningFactCard("material", PlanningFactCategory.BUSINESS_RULE, "docs/病案质量方案.md", evidence);
    }

    private static String table(String name, String value) {
        return "| 指标 | 分母 |\n|---|---|\n| " + name + " | " + value + " |";
    }

    private static OptimizationResult assemble(String output, String raw, List<PlanAnswer> answers,
                                              boolean confirmed, List<PlanningFactCard> facts) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "用户提供病案质量方案。"),
                new PromptSection(PromptSectionType.TASK, "任务", "制定病案质量研究方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得编造数据。")), "test", "test", false, List.of());
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "方法及指标表", "忠于资料", "示例"),
                List.of(), answers, confirmed, List.of("不得编造数据"), false, 1, raw, facts, List.of());
    }
}
