package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回放未决分母在表格中被擅自确定，以及跨对象推荐、短说明复写和纯交互形式追问。
 * 用不同对象、新条件和主动选择反例保护共享增强与 Plan 链路。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DecisionDeliveryConsistencyRegressionTest {
    private static final String RAW = "制定病案质量研究方案，交付指标表及必要伪代码。跨字段一致性指标分母尚未确定。";
    private static final List<PlanAnswer> ANSWERS = List.of(new PlanAnswer("denominator",
            "跨字段一致性指标分母采用什么口径？", "暂不确定。跨字段一致性指标分母尚未决定。"));

    @Test
    void putsThePendingStatusContractInTheCopyableBody() {
        var result = assemble("交付指标表与必要伪代码。", ANSWERS, true, RAW);
        assertThat(result.optimizedPrompt()).contains("正文、表格、公式", "待确认", "伪代码");
    }

    @Test
    void rejectsAnAssignedTableDenominatorEvenWhenTheNarrativeSaysPending() {
        assertThatThrownBy(() -> assemble("一致性分母仍未决定。\n| 指标 | 分母 |\n|---|---|\n"
                + "| 跨字段一致性指标 | 所有有效出院记录数 |", ANSWERS, true, RAW))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void rejectsAnAssignedDenominatorInAnExplicitStatement() {
        assertThatThrownBy(() -> assemble("跨字段一致性指标分母采用所有有效出院记录。", ANSWERS, true, RAW))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void rejectsTheActualDateConsistencySubindicatorFromTheFailedWork() {
        assertThatThrownBy(() -> assemble("| 指标 | 分子 | 分母 |\n|---|---|---|\n"
                + "| 出院日期早于入院日期不一致率 | 出院日早于入院日的记录数 | 入院日期与出院日期均格式合法的有效出院记录数 |",
                ANSWERS, true, RAW)).isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void preservesAnExplicitNegativeInstructionAndAnotherNamedIndicator() {
        var result = assemble("不得将跨字段一致性指标分母设为所有有效出院记录。\n"
                + "| 指标 | 分母 |\n|---|---|\n| 新生儿完整性指标 | 新生儿有效出院记录数 |", ANSWERS, true, RAW);
        assertThat(result.optimizedPrompt()).contains("不得将", "新生儿有效出院记录数");
    }

    @Test
    void rejectsAConcreteValueDisguisedByAnUnknownFootnote() {
        assertThatThrownBy(() -> assemble("| 指标 | 分母 |\n|---|---|\n"
                + "| 跨字段一致性指标 | 所有有效出院记录数（待确认） |", ANSWERS, true, RAW))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void acceptsATrueConfirmationWithoutApplyingTheOldPendingQuestion() {
        var answers = List.of(new PlanAnswer("denominator", "跨字段一致性指标分母采用什么口径？",
                "跨字段一致性指标分母采用所有有效出院记录。"));
        var result = assemble("| 指标 | 分母 |\n|---|---|\n| 跨字段一致性指标 | 所有有效出院记录数 |",
                answers, true, "制定病案质量研究方案。");
        assertThat(result.ambiguities()).isEmpty();
    }

    @Test
    void supersedesTheOriginalUnknownStateOnlyAfterTheSameParameterIsActuallyConfirmed() {
        var answers = List.of(new PlanAnswer("denominator", "跨字段一致性指标分母采用什么口径？",
                "跨字段一致性指标分母采用所有有效出院记录。"));
        var result = assemble("| 指标 | 分母 |\n|---|---|\n| 跨字段一致性指标 | 所有有效出院记录数 |",
                answers, true, RAW);
        assertThat(result.optimizedPrompt()).contains("所有有效出院记录数");
    }

    @Test
    void doesNotUseAFutureConditionalAnswerToResolveTheOriginalUnknownState() {
        var answers = List.of(new PlanAnswer("denominator", "跨字段一致性指标分母采用什么口径？",
                "如果以后确认跨字段一致性指标分母采用所有有效出院记录，再按该口径计算。"));
        assertThatThrownBy(() -> assemble("| 指标 | 分母 |\n|---|---|\n| 跨字段一致性指标 | 所有有效出院记录数 |",
                answers, true, RAW)).isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void keepsUnknownTableCellsAndDoesNotBorrowCompletenessConfirmation() {
        var answers = List.of(ANSWERS.getFirst(), new PlanAnswer("completeness", "完整性指标分母如何确定？",
                "完整性指标分母采用所有有效出院记录。"));
        var result = assemble("| 指标 | 分母 |\n|---|---|\n| 完整性指标 | 所有有效出院记录数 |\n"
                + "| 跨字段一致性指标 | 待确认；确认前不计算 |", answers, true, RAW);
        assertThat(result.optimizedPrompt()).contains("所有有效出院记录数", "确认前不计算");
    }

    @Test
    void restoresAnExplicitUnresolvedStatementWhenDirectProviderReturnsNoFindings() {
        var result = assemble("交付指标表与方法步骤。", List.of(), false, RAW);
        assertThat(result.ambiguities()).anyMatch(value -> value.contains("一致性指标分母"));
    }

    @Test
    void doesNotConvertAFutureConditionIntoACurrentUnresolvedStatement() {
        var result = assemble("只输出译文。", List.of(), false,
                "翻译下面的说明，保留条件：如果以后确认一致性指标分母尚未确定，再补充说明。只输出译文。");
        assertThat(result.ambiguities()).isEmpty();
    }

    @Test
    void mergesTheShortHospitalExplanationOnlyInsideItsBoundDecision() {
        var answers = List.of(new PlanAnswer("windowA", "甲院的比较观察窗口如何定义？",
                "暂不确定。甲院的比较观察窗口需要院方后续核实；不能用另一院的窗口替代。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("甲院的比较观察窗口如何定义？需院方后续核实。"), List.of(), List.of());
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst()).doesNotContain("补充说明：需院方后续核实");
        var novel = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("甲院的比较观察窗口如何定义？需院方后续核实2023年窗口。"), List.of(), List.of());
        assertThat(novel.executionPrerequisites()).anyMatch(value -> value.contains("2023年窗口"));
    }

    @Test
    void doesNotUseAnotherHospitalAsRecommendationEvidence() {
        var question = windowQuestion("乙医院");
        var digest = digest("甲医院的观察窗口采用24小时。");
        var aligned = PlanRecommendationAligner.align(question, request("整理两院观察窗口方案。", digest));
        assertThat(aligned.options()).noneMatch(PlanOption::recommended);
        assertThat(aligned.options()).extracting(PlanOption::answer)
                .containsExactlyElementsOf(question.options().stream().map(PlanOption::answer).toList());
    }

    @Test
    void keepsTheSameHospitalRecommendation() {
        assertThat(PlanRecommendationAligner.align(windowQuestion("甲医院"),
                request("整理甲医院观察窗口方案。", digest("甲医院的观察窗口采用24小时。"))).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("24h");
    }

    @Test
    void doesNotBorrowAnotherYearAndKeepsTheApprovalQualification() {
        assertThat(PlanRecommendationAligner.align(windowQuestion("甲医院2023年"),
                request("整理窗口方案。", digest("甲医院2022年的观察窗口采用24小时。"))).options())
                .noneMatch(PlanOption::recommended);
        assertThat(PlanRecommendationAligner.align(windowQuestion("甲医院"),
                request("整理窗口方案。", digest("甲医院的观察窗口采用24小时。该规则的审批状态尚未明确。"))).options())
                .noneMatch(PlanOption::recommended);
    }

    @Test
    void keepsASpecifiedWindowWhenOnlyAnotherRuleNeedsApproval() {
        assertThat(PlanRecommendationAligner.align(windowQuestion("甲医院"),
                request("整理窗口方案。", digest("甲医院的观察窗口采用24小时。该退款规则的审批状态尚未明确。"))).options())
                .filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("24h");
    }

    @Test
    void doesNotTreatTheSemicolonInsideACompleteAnswerAsAnApprovalBoundaryBypass() {
        var question = new PlanQuestion("window", "甲医院的观察窗口采用什么口径？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("24h", "24小时", "窗口", "观察窗口采用24小时；", true),
                        new PlanOption("48h", "48小时", "窗口", "观察窗口采用48小时；", false)), List.of(), true);
        assertThat(PlanRecommendationAligner.align(question,
                request("整理窗口方案。", digest("甲医院的观察窗口采用24小时；该规则的审批状态尚未明确。"))).options())
                .noneMatch(PlanOption::recommended);
    }

    @Test
    void delegatesOnlyTheAlreadySpecifiedConfirmationDialogShape() {
        var question = dialogQuestion("", "使用确认框展示候选并供用户确认。");
        var input = request("开发Vue 3表单，匹配到候选后弹出确认框，用户确认后才填充。", null);
        assertThat(new PlanQuestionFilter().filter(List.of(question), input)).isEmpty();
    }

    @Test
    void retainsANewDialogPermissionAndAnExplicitUserChoice() {
        var question = dialogQuestion("需确认跨租户权限。", "使用确认框展示候选并供用户确认。");
        var input = request("开发Vue 3表单，匹配到候选后弹出确认框，用户确认后才填充。", null);
        assertThat(new PlanQuestionFilter().filter(List.of(question), input)).hasSize(1);
        assertThat(new PlanQuestionFilter().filter(List.of(dialogQuestion("", "使用确认框展示候选并供用户确认。")),
                request("开发Vue 3表单，先让我选择确认交互形式，确认框只是候选。", null))).hasSize(1);
    }

    @Test
    void doesNotDeleteAnOptionThatChangesOverwriteBehaviorEvenWithAStyleLabel() {
        assertThat(new PlanQuestionFilter().filter(List.of(dialogQuestion("", "使用确认框，确认后覆盖全部已有值。")),
                request("开发Vue 3表单，匹配后使用确认框。", null))).hasSize(1);
    }

    private static PlanQuestion dialogQuestion(String hint, String answer) {
        return new PlanQuestion("dialog", "确认交互应该使用确认框还是弹窗？", hint, PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("dialog", "确认框", "展示候选", answer, true),
                        new PlanOption("modal", "弹窗", "展示候选", "使用弹窗展示候选并供用户确认。", false)), List.of(), true);
    }

    private static PlanQuestion windowQuestion(String owner) {
        return new PlanQuestion("window", owner + "的观察窗口采用什么口径？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("24h", "24小时", "窗口", "观察窗口采用24小时。", true),
                        new PlanOption("48h", "48小时", "窗口", "观察窗口采用48小时。", false)), List.of(), true);
    }

    private static PlanningProviderRequest request(String raw, PlanningContextDigest digest) {
        return new PlanningProviderRequest(raw, "", List.of(), digest);
    }

    private static PlanningContextDigest digest(String fact) {
        return new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(fact), "COMPLETE", 1, List.of());
    }

    private static OptimizationResult assemble(String output, List<PlanAnswer> answers, boolean confirmed, String raw) {
        var provider = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "用户提供病案质量资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", "整理研究方法。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得编造数据。")), "test", "test", false, List.of());
        return new OptimizationResultAssembler().assemble(provider,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "输出", "忠于资料", "示例"),
                List.of(), answers, confirmed, List.of("不得编造数据"), false, 1, raw);
    }
}
