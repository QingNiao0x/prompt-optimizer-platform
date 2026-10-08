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
        assertThat(result.optimizedPrompt()).contains("用户已要求的交付形式", "待确认", "原定伪代码仍须交付");
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
    void extractsAPendingParameterBeforeItsFollowingNegativeInstruction() {
        var answers = List.of(new PlanAnswer("denominator", "跨字段一致性指标分母采用什么口径？",
                "暂不确定。跨字段一致性指标分母尚未决定，不能直接继承完整性指标分母；后续须按各逻辑规则的适用记录核实。"));
        assertThatThrownBy(() -> assemble("| 指标 | 分母 |\n|---|---|\n"
                + "| 跨字段一致性指标 | 所有有效出院记录数 |", answers, true, "制定病案质量研究方案。"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void namesThePendingParameterAndForbidsAUniversalDenominatorInTheCopyableBody() {
        var result = assemble("交付指标表与必要伪代码。", ANSWERS, true, RAW);
        assertThat(result.optimizedPrompt()).contains("跨字段一致性指标", "分母", "分别命名", "通用变量", "仍须交付");
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
    void removesTheRepeatedPeerWindowProhibitionButKeepsTheComparisonExplanation() {
        var answers = List.of(new PlanAnswer("windowA", "甲院的比较观察窗口如何定义？",
                        "暂不确定。甲院的比较观察窗口需要院方后续核实；不能用另一院的窗口替代。"),
                new PlanAnswer("windowB", "乙院的比较观察窗口如何定义？",
                        "暂不确定。乙院的比较观察窗口需要院方后续核实；不能用另一院的窗口替代。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers), "甲院与乙院窗口分别确认。")
                .merge(List.of("甲院的比较观察窗口如何定义？不能用乙院窗口替代，该未决条件影响甲院统计口径与两院比较结果。"),
                        List.of(), List.of());
        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages().getFirst()).doesNotContain("不能用乙院窗口替代").contains("影响甲院统计口径");
    }

    @Test
    void preservesANewPeerAndYearEvenWhenTheExistingWindowProhibitionMatches() {
        var answers = List.of(new PlanAnswer("windowA", "甲院的比较观察窗口如何定义？",
                "暂不确定。甲院的比较观察窗口需要院方后续核实；不能用另一院的窗口替代。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers), "甲院与乙院窗口分别确认。")
                .merge(List.of("甲院的比较观察窗口如何定义？不能用丙院窗口替代，需核实2023年窗口。"), List.of(), List.of());
        assertThat(result.messages().getFirst()).contains("不能用丙院窗口替代", "2023年窗口");
    }

    @Test
    void removesOnlyTheAlreadyBoundAbnormalRateCalculationImpact() {
        var answers = List.of(new PlanAnswer("thresholdA", "甲院异常等待阈值是多少？",
                "暂不确定。甲院的异常等待阈值尚未决定；相关异常比例的计算需等待阈值确认。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("甲院异常等待阈值是多少？该未决条件影响甲院异常比例计算。"), List.of(), List.of());
        assertThat(result.messages().getFirst()).doesNotContain("补充说明");
        var novel = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("甲院异常等待阈值是多少？该未决条件影响乙院异常比例计算。"), List.of(), List.of());
        assertThat(novel.messages().getFirst()).contains("影响乙院异常比例计算");
    }

    @Test
    void routesARepeatedComparisonDecisionToItsOwnBoundItemInsteadOfRepeatingItUnderCoverage() {
        var answers = List.of(new PlanAnswer("coverage", "A医院2022年的覆盖度如何？",
                        "暂不确定。A医院2022年覆盖度尚未核实。"),
                new PlanAnswer("handling", "A医院2022年覆盖度未核实时，年度与医院比较如何处理该年数据？",
                        "暂不确定。A医院2022年覆盖度未核实时，该年数据的比较处理方式尚未决定；不能自动排除或认定已具备直接可比性。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("A医院2022年的覆盖度如何？该年数据的年度与医院比较处理方式未定；不能自动排除或认定已具备直接可比性。"),
                List.of(), List.of());
        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages().getFirst()).doesNotContain("比较处理方式", "补充说明");
        assertThat(result.messages().getLast()).contains("比较处理方式尚未决定", "不能自动排除");
        var differentYear = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of(answers.getFirst(),
                new PlanAnswer("handling", "A医院2023年覆盖度未核实时，年度与医院比较如何处理？",
                        "暂不确定。A医院2023年覆盖度未核实时，该年数据的比较处理方式尚未决定。")))).merge(
                List.of("A医院2022年的覆盖度如何？该年数据的年度与医院比较处理方式未定；需保留2022年限制。"), List.of(), List.of());
        assertThat(differentYear.messages().getFirst()).contains("比较处理方式未定", "2022年限制");
    }

    @Test
    void resolvesTheSameBoundExampleReferenceButDoesNotDiscardANewExampleValue() {
        var answers = List.of(new PlanAnswer("thresholds", "异常等待的阈值应如何确定？",
                "暂不确定。甲院的异常等待阈值尚未决定，不采用资料中60分钟示例值。乙院的异常等待阈值尚未决定，也不采用该示例值；两院相关异常比例的计算分别等待本院阈值确认。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("乙院的异常等待阈值尚未决定，不采用资料中60分钟示例值。"), List.of(), List.of());
        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages().getLast()).doesNotContain("补充说明：不采用资料中60分钟示例值");
        var novel = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("乙院的异常等待阈值尚未决定，不采用资料中90分钟示例值。"), List.of(), List.of());
        assertThat(novel.messages().getLast()).contains("90分钟示例值");
    }

    @Test
    void removesASameBoundConfirmationRequestButKeepsAnotherHospitalAndYear() {
        var answers = List.of(new PlanAnswer("coverage", "A医院2022年出院病案首页的覆盖度如何确定？",
                "暂不确定。A医院2022年覆盖度尚未核实，其他年份与医院不能替该项建立覆盖事实。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("A医院2022年出院病案首页的覆盖度如何确定？需确认A医院2022年覆盖度状态。",
                        "A医院2022年出院病案首页的覆盖度如何确定？请确认如何确定该覆盖度。"), List.of(), List.of());
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst()).doesNotContain("补充说明");
        var novel = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("A医院2022年出院病案首页的覆盖度如何确定？需确认A医院2023年覆盖度状态；需确认B医院2022年覆盖度状态。"),
                List.of(), List.of());
        assertThat(novel.executionPrerequisites()).anyMatch(value -> value.contains("A医院2023年")
                && value.contains("B医院2022年"));
    }

    @Test
    void compactsTheBoundConsistencyVerificationButKeepsANewRecordScope() {
        var answers = List.of(new PlanAnswer("consistency", "跨字段逻辑一致性指标的统计分母采用什么口径？",
                "暂不确定。跨字段一致性指标的分母尚未决定，不能直接继承完整性指标分母；后续须按各逻辑规则的适用记录核实。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("跨字段逻辑一致性指标的统计分母采用什么口径？需按各逻辑规则的适用记录核实并确认口径；请确认各逻辑规则的适用记录及分母定义。"),
                List.of(), List.of());
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst()).doesNotContain("补充说明");
        var novel = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("跨字段逻辑一致性指标的统计分母采用什么口径？请确认各逻辑规则的适用记录及退款分母定义。"),
                List.of(), List.of());
        assertThat(novel.executionPrerequisites()).anyMatch(value -> value.contains("退款分母定义"));
    }

    @Test
    void removesOnlyTheCoveredImputationRequestAndPreservesTheConditionalMethod() {
        var answers = List.of(new PlanAnswer("imputation", "是否对缺失病例使用插补？",
                "暂不确定。是否插补、适用指标和方法均未决定，本次保持缺失状态并说明不同处理的影响，不把默认不插补写成已确认专业决定。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("是否对缺失病例使用插补？且不同指标可能采用不同处理；需确认是否插补、适用指标和方法。"),
                List.of(), List.of());
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst()).contains("不同指标可能采用不同处理")
                .doesNotContain("需确认是否插补");
        var novel = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(
                List.of("是否对缺失病例使用插补？如果采用多重插补，需确认是否插补、适用指标和方法；需确认20%缺失时的处理。"),
                List.of(), List.of());
        assertThat(novel.executionPrerequisites()).anyMatch(value -> value.contains("如果采用多重插补")
                && value.contains("20%缺失"));
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

    @Test
    void doesNotReaskTheExplicitConditionalConflictPresentationRule() {
        var q = new PlanQuestion("presentation", "若两份资料对同一指标给出不同阈值，方案中应如何呈现？",
                "需确认是否保留两个取值并列。", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        var input = request("若两份资料给出不同阈值，应把涉及的指标、医院、适用时间和两个取值说明清楚。", null);
        assertThat(new PlanQuestionFilter().filter(List.of(q), input)).isEmpty();
        var named = new PlanQuestion("context-conflict-threshold", "甲院2023年阈值30与60冲突，应采用哪个？",
                "实际来源有两个取值。", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(named), input)).hasSize(1);
        var additional = new PlanQuestion("presentation", q.question(), "另外需确认对外报告的隐私权限。",
                PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(additional), input)).hasSize(1);
        assertThat(new PlanQuestionFilter().filter(List.of(q), request("资料有两个阈值，目前不知道如何比较。", null)))
                .hasSize(1);
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
