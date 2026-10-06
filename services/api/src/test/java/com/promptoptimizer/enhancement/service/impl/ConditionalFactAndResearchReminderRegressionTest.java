package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
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
 * 回放科研真实输出中的假设固化、跨题分母重复与插补改写，保留新对象及新条件对照。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ConditionalFactAndResearchReminderRegressionTest {
    private static final String RAW = "制定A医院和B医院的病案质量方法方案，只交付方案。"
            + "若我只确认完整性指标分母包含所有有效出院记录，不能据此推断跨字段一致性指标也采用相同分母。"
            + "是否对缺失病例使用插补尚未决定，且不同指标可能采用不同处理，不得默认统一插补。";
    private static final String IMPUTATION = "是否插补、适用指标和方法均未决定，本次保持缺失状态并说明不同处理的影响，"
            + "不把默认不插补写成已确认专业决定。";
    private static final String IMPUTATION_REWRITE = "是否对缺失病例使用插补、适用哪些指标及采用何种方法均未决定；"
            + "不同处理会影响缺失指标结果与解释，本次保持缺失状态并说明不同处理的影响，"
            + "不把默认不插补写成已确认专业决定。";

    @Test
    void rejectsAConditionalPremisePromotedToConfirmationInBackground() {
        assertThatThrownBy(() -> assemble("用户已确认完整性指标分母包含所有有效出院记录。", "忠于资料。",
                List.of(), List.of(), false, RAW)).isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void rejectsFalseConfirmationEvenWhenTheSameReminderContainsAnotherUnknown() {
        String finding = "完整性指标分母与一致性指标分母尚需分别确定；用户已确认完整性指标分母包含所有有效出院记录，"
                + "但不能据此推断跨字段一致性指标也采用相同分母。";
        assertThatThrownBy(() -> assemble("病案方法方案。", "忠于资料。", List.of(finding), List.of(), false, RAW))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void keepsConditionalInstructionsAndDoesNotRejectAnActualBoundConfirmation() {
        var conditional = assemble("病案方法方案。", "如果以后确认完整性指标分母包含所有有效出院记录，再更新对应指标。",
                List.of(), List.of(), false, RAW);
        assertThat(conditional.optimizedPrompt()).contains("如果以后确认");
        var confirmed = assemble("用户已确认完整性指标分母包含所有有效出院记录。", "一致性分母仍需单独确定。",
                List.of(), List.of(new PlanAnswer("denominator", "完整性指标分母采用哪种口径？",
                        "完整性指标分母包含所有有效出院记录。")), true, RAW);
        assertThat(confirmed.optimizedPrompt()).contains("用户已确认完整性指标分母包含所有有效出院记录");
    }

    @Test
    void doesNotUseAConditionalAnswerAsProofOfAnEstablishedFact() {
        assertThatThrownBy(() -> assemble("用户已确认完整性指标分母包含所有有效出院记录。", "忠于资料。",
                List.of(), List.of(new PlanAnswer("denominator", "完整性指标分母采用哪种口径？",
                        "如果以后确认完整性指标分母包含所有有效出院记录，再更新方法。")), true, RAW))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void checksConditionalAnswersEvenWhenTheOriginalRequirementDoesNotContainTheHypothesis() {
        assertThatThrownBy(() -> assemble("用户已确认完整性指标分母包含所有有效出院记录。", "忠于资料。",
                List.of(), List.of(new PlanAnswer("denominator", "完整性指标分母采用哪种口径？",
                        "如果以后确认完整性指标分母包含所有有效出院记录，再更新方法。")), true, "制定研究方法方案。"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void allowsAnExplicitActualConfirmationInTheOriginalRequirementAndKeepsNegativeQuotes() {
        var result = assemble("用户已确认完整性指标分母包含所有有效出院记录。", "忠于资料。", List.of(), List.of(), false,
                RAW + "用户已确认完整性指标分母包含所有有效出院记录。");
        assertThat(result.optimizedPrompt()).contains("用户已确认完整性指标分母包含所有有效出院记录");
        var quote = assemble("不要写成用户已确认完整性指标分母包含所有有效出院记录。", "忠于资料。",
                List.of(), List.of(), false, RAW);
        assertThat(quote.optimizedPrompt()).contains("不要写成用户已确认");
    }

    @Test
    void mergesAnImputationRewriteWithoutDiscardingItsNewExplanation() {
        var result = assemble("病案方法方案。", "忠于资料。", List.of(IMPUTATION_REWRITE),
                List.of(new PlanAnswer("imputation", "是否对缺失病例使用插补？适用哪些指标及采用何种方法？",
                        "暂不确定。" + IMPUTATION)), true, RAW);
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(result.optimizedPrompt()).contains("不同处理会影响缺失指标结果与解释")
                .contains("不把默认不插补写成已确认专业决定");
        assertThat(occurrences(result.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(1);
    }

    @Test
    void mergesTheSameConsistencyDenominatorNamedByTwoBoundQuestions() {
        var result = assemble("病案方法方案。", "忠于资料。", List.of(), List.of(
                new PlanAnswer("complete", "完整性指标分母采用哪种口径？",
                        "完整性指标分母采用所有有效出院记录，包括相应字段缺失的记录。"
                                + "仅确认完整性分母，一致性指标分母仍未决定。"),
                new PlanAnswer("consistent", "跨字段一致性指标的分母采用哪种口径？",
                        "暂不确定。跨字段一致性指标的分母尚未决定，不能直接继承完整性指标分母；"
                                + "后续须按各逻辑规则的适用记录核实。")), true, RAW);
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(result.optimizedPrompt()).contains("不能直接继承完整性指标分母", "后续须按各逻辑规则的适用记录核实");
    }

    @Test
    void keepsOtherHospitalsNewImputationConditionsAndSeparateDenominators() {
        var answers = List.of(new PlanAnswer("imputation-a", "A医院是否对缺失病例使用插补？",
                "暂不确定。A医院是否插补、适用指标和方法均未决定。"));
        var result = assemble("病案方法方案。", "忠于资料。", List.of(
                "B医院是否对缺失病例使用插补、适用哪些指标及采用何种方法均未决定。",
                "A医院缺失比例>20%时是否插补、适用指标和方法均未决定。",
                "完整性指标的分母尚未决定。", "跨字段一致性指标的分母尚未决定。"), answers, true, RAW);
        assertThat(result.ambiguities()).hasSize(5);
        assertThat(result.optimizedPrompt()).contains("B医院", "缺失比例>20%", "完整性指标的分母", "跨字段一致性指标的分母");
    }

    @Test
    void mergesTheActualFlashRewriteUnderAReorderedImputationQuestion() {
        var result = assemble("病案方法方案。", "忠于资料。", List.of(
                        "是否对缺失病例使用插补、适用指标和方法均未决定；本次保持缺失状态并说明不同处理的影响，"
                                + "不把默认不插补写成已确认专业决定。"),
                List.of(new PlanAnswer("imputation", "缺失病例是否采用插补？", "暂不确定。" + IMPUTATION)), true, RAW);
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(occurrences(result.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(1);
    }

    @Test
    void removesRepeatedDenominatorInstructionsButKeepsNewImpactAndConditionalRules() {
        var answer = new PlanAnswer("consistency", "跨字段逻辑一致性指标的分母应如何定义？",
                "暂不确定。跨字段一致性指标的分母尚未决定，不能直接继承完整性指标分母；"
                        + "后续须按各逻辑规则的适用记录核实。");
        var result = assemble("病案方法方案。", "忠于资料。", List.of(
                "跨字段逻辑一致性指标的分母尚未决定，不能直接继承完整性指标分母；"
                        + "后续须按各逻辑规则的适用记录核实，该缺口会影响一致性指标的分子分母口径。"),
                List.of(answer), true, RAW);
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(occurrences(result.optimizedPrompt(), "不能直接继承完整性指标分母"))
                .as("可复制正文：%s", result.optimizedPrompt()).isEqualTo(1);
        assertThat(occurrences(result.optimizedPrompt(), "后续须按各逻辑规则的适用记录核实")).isEqualTo(1);
        assertThat(result.optimizedPrompt()).contains("该缺口会影响一致性指标的分子分母口径");
        var conditional = assemble("病案方法方案。", "忠于资料。", List.of(
                "插补方法尚未决定。若采用回归插补，必须保留合法零值。"),
                List.of(new PlanAnswer("imputation", "插补方法采用哪一种？",
                        "暂不确定。插补方法尚未决定。若采用MICE，必须保留合法零值。")), true, RAW);
        assertThat(conditional.optimizedPrompt()).contains("若采用MICE，必须保留合法零值", "若采用回归插补，必须保留合法零值");
    }

    @Test
    void doesNotInventAnUnknownDecisionFromABoundaryStatement() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("zero", "住院日为0的记录如何处理？",
                "住院日为0是合法有效数值，不能仅因0视为缺失；这个规则不替代尚未决定的各指标分母。")));
        assertThat(decisions.pendingDecisions()).isEmpty();
        assertThat(decisions.knownDecisions()).hasSize(1);
        var actualUnknown = ConfirmedDecisionSet.from(List.of(new PlanAnswer("zero", "住院日为0的记录如何处理？",
                "住院日为0是合法有效数值。跨字段一致性指标分母尚未决定。")));
        assertThat(actualUnknown.pendingDecisions()).hasSize(1);
    }

    @Test
    void mergesACompoundImputationRestatementAndUnverifiedCoverageFromActualProOutput() {
        var result = assemble("病案方法方案。", "忠于资料。", List.of(
                "是否对缺失数据采用插补尚未决定，适用指标和方法均未确定；",
                "A医院2022年数据覆盖度尚未核实，其他年份与医院不能替该项建立覆盖事实。"),
                List.of(new PlanAnswer("imputation", "是否对缺失数据采用插补？", "暂不确定。" + IMPUTATION),
                        new PlanAnswer("coverage", "A医院2022年的数据覆盖度如何？",
                                "暂不确定。A医院2022年覆盖度尚未核实，其他年份与医院不能替该项建立覆盖事实。")), true, RAW);
        assertThat(result.ambiguities()).hasSize(2);
        assertThat(result.optimizedPrompt()).doesNotContain("补充说明：是否对缺失数据采用插补");
        assertThat(occurrences(result.optimizedPrompt(), "其他年份与医院不能替该项建立覆盖事实")).isEqualTo(1);
        var different = assemble("病案方法方案。", "忠于资料。", List.of(
                "B医院2022年数据覆盖度尚未核实。", "A医院2023年数据覆盖度尚未核实。",
                "是否对新生儿缺失数据采用插补尚未决定，适用指标和方法均未确定。"),
                List.of(new PlanAnswer("coverage", "A医院2022年的数据覆盖度如何？",
                        "暂不确定。A医院2022年覆盖度尚未核实。"),
                        new PlanAnswer("imputation", "是否对缺失数据采用插补？", "暂不确定。" + IMPUTATION)), true, RAW);
        assertThat(different.ambiguities()).hasSize(5);
    }

    @Test
    void mergesActualAdoptAndPerformImputationWordingAndOriginalDenominatorName() {
        for (String[] wording : List.of(new String[]{"对缺失病例是否采用插补？", "是否对缺失病例采用插补"},
                new String[]{"是否对缺失值进行插补？若插补，采用何种方法？", "是否对缺失值进行插补"})) {
            var result = assemble("病案方法方案。", "忠于资料。", List.of(
                    wording[1] + "、适用指标和方法均未决定，本次保持缺失状态并说明不同处理的影响，不把默认不插补写成已确认专业决定。",
                    "跨字段逻辑不一致指标的统计分母尚未决定。"), List.of(
                    new PlanAnswer("complete", "完整性指标分母如何定义？",
                            "完整性指标分母包含所有有效出院记录。仅确认完整性分母，一致性指标分母仍未决定。"),
                    new PlanAnswer("imputation", wording[0], "暂不确定。" + IMPUTATION),
                    new PlanAnswer("denominator", "跨字段逻辑不一致指标的统计分母应如何定义？",
                            "暂不确定。跨字段一致性指标的分母尚未决定，不能直接继承完整性指标分母；后续须按各逻辑规则的适用记录核实。")), true, RAW);
            assertThat(result.ambiguities()).hasSize(2);
            assertThat(result.optimizedPrompt()).doesNotContain("补充说明：是否对", "补充说明：跨字段逻辑不一致");
            assertThat(occurrences(result.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(1);
        }
    }

    @Test
    void mergesTheKnownImputationPartAndRepeatedInstructionButKeepsItsNewImpact() {
        var result = assemble("病案方法方案。", "忠于资料。", List.of(
                "是否对缺失病例使用插补尚未决定，且不同指标可能采用不同处理；该缺口会影响缺失数据的处理方式和结果解释，不得默认统一插补。",
                "跨字段一致性指标分母尚未决定，需按各逻辑规则的适用记录分别核实。"), List.of(
                new PlanAnswer("imputation", "缺失病例是否采用插补？", "暂不确定。" + IMPUTATION),
                new PlanAnswer("complete", "完整性指标分母如何定义？", "完整性指标分母包含所有有效出院记录。仅确认完整性分母，一致性指标分母仍未决定。"),
                new PlanAnswer("consistency", "跨字段一致性指标分母采用什么口径？",
                        "暂不确定。跨字段一致性指标的分母尚未决定，不能直接继承完整性指标分母；后续须按各逻辑规则的适用记录核实。")), true, RAW);
        assertThat(result.ambiguities()).hasSize(2);
        assertThat(result.optimizedPrompt()).doesNotContain("补充说明：需按各逻辑规则的适用记录分别核实");
        assertThat(result.optimizedPrompt()).contains("该缺口会影响缺失数据的处理方式和结果解释", "不同指标可能采用不同处理");
    }

    @Test
    void keepsOneAuthoritativeBodyCopyAndPreservesNewConditionsFromProviderLists() {
        String coverage = "A医院2022年覆盖度尚未核实，其他年份与医院不能替该项建立覆盖事实。";
        var answers = List.of(new PlanAnswer("imputation", "缺失病例是否采用插补？", "暂不确定。" + IMPUTATION),
                new PlanAnswer("coverage", "A医院2022年覆盖度如何？", "暂不确定。" + coverage));
        var result = assemble("病案方法方案。", "5. " + coverage + "\n6. " + IMPUTATION,
                List.of(), answers, true, RAW);
        assertThat(occurrences(result.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(1);
        assertThat(occurrences(result.optimizedPrompt(), "其他年份与医院不能替该项建立覆盖事实")).isEqualTo(1);
        var compound = providerWithTask("制定病案方法方案。\n未决事项（保持可见，不默认统一处理）："
                + IMPUTATION.replaceFirst("。$", "") + "；" + coverage, "忠于资料。");
        var assembled = new OptimizationResultAssembler().assemble(compound,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "输出", "结果可核对", "示例"),
                List.of(), answers, true, List.of("不得泄露记录标识。"), false, 1, RAW);
        assertThat(occurrences(assembled.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(1);
        assertThat(assembled.optimizedPrompt()).contains("保持可见", "不默认统一处理");
        var conditional = assemble("病案方法方案。", "若采用MICE，必须保留合法零值。\n"
                + "A医院2023年覆盖度尚未核实。\n| 指标 | 状态 |\n| 插补 | " + IMPUTATION + " |\n"
                + "### 另一研究组\n" + IMPUTATION,
                List.of(), answers, true, RAW);
        assertThat(conditional.optimizedPrompt()).contains("若采用MICE，必须保留合法零值", "A医院2023年覆盖度尚未核实", "| 插补 |", "### 另一研究组\n" + IMPUTATION);
    }

    @Test
    void alignsTheSingleRawImputationObjectButKeepsDistinctObjectsAndNovelLimitations() {
        String repeated = "是否对缺失病例使用插补尚未决定，且不同指标可能采用不同处理，不得默认统一插补。";
        var answer = new PlanAnswer("imputation", "本次方法方案对缺失值是否采用插补？", "暂不确定。" + IMPUTATION);
        var result = assemble("病案方法方案。", repeated, List.of(), List.of(answer), true, RAW);
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(occurrences(result.optimizedPrompt(), "是否对缺失病例使用插补尚未决定")).isEqualTo(0);
        assertThat(result.optimizedPrompt()).contains("不同指标可能采用不同处理", "不得默认统一插补");
        String multiple = RAW + "是否对新生儿病例使用插补尚未决定。";
        var different = assemble("病案方法方案。", "是否对新生儿病例使用插补尚未决定。",
                List.of("是否对新生儿病例使用插补尚未决定。"), List.of(answer), true, multiple);
        assertThat(different.ambiguities()).hasSize(2);
        assertThat(different.optimizedPrompt()).contains("是否对新生儿病例使用插补尚未决定");
    }

    @Test
    void mergesTheExplicitTopicPrefixAndConcreteMethodRestatementWithoutBorrowingOtherObjects() {
        var answer = new PlanAnswer("imputation", "对于缺失数据，是否采用插补？若采用，请说明具体方法。",
                "暂不确定。" + IMPUTATION);
        var result = assemble("病案方法方案。", "忠于资料。", List.of(
                "是否对缺失数据采用插补、适用指标及具体方法尚未决定，本次保持缺失状态并说明不同处理的影响。"),
                List.of(answer), true, RAW);
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(occurrences(result.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(1);
        var different = assemble("病案方法方案。", "忠于资料。", List.of(
                "是否对新生儿数据采用插补、适用指标及具体方法尚未决定。"), List.of(answer), true, RAW);
        assertThat(different.ambiguities()).hasSize(2);
    }

    @Test
    void doesNotBackfillADenominatorReminderAlreadyFullyPresentInTheAuthoritativeList() {
        String answer = "暂不确定。跨字段一致性指标的分母尚未决定，不能直接继承完整性指标分母；后续须按各逻辑规则的适用记录核实。";
        var result = assemble("病案方法方案。", "忠于资料。", List.of(),
                List.of(new PlanAnswer("complete", "完整性指标分母如何定义？",
                                "完整性指标分母采用所有有效出院记录，包括相应字段缺失的记录；有效是指去重后满足研究时间和对象范围的记录，不因该字段缺失而排除。仅确认完整性分母，一致性指标分母仍未决定。"),
                        new PlanAnswer("consistency", "跨字段一致性指标的分母应如何定义？", answer)), true, RAW);
        assertThat(occurrences(result.optimizedPrompt(), "不能直接继承完整性指标分母"))
                .as("可复制正文：%s", result.optimizedPrompt()).isEqualTo(1);
        assertThat(occurrences(result.optimizedPrompt(), "后续须按各逻辑规则的适用记录核实"))
                .as("可复制正文：%s", result.optimizedPrompt()).isEqualTo(1);
    }

    @Test
    void removesOnlyTheIndependentPendingSentenceAfterActualConfirmationAndFromOutput() {
        var answers = List.of(new PlanAnswer("complete", "完整性指标分母如何定义？",
                        "完整性指标分母采用所有有效出院记录。"),
                new PlanAnswer("imputation", "对于缺失数据，是否采用插补？", "暂不确定。" + IMPUTATION));
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "病案方法方案。"),
                new PromptSection(PromptSectionType.TASK, "任务", "制定方法方案。\n9. 已确认决定：完整性指标分母采用所有有效出院记录。"
                        + "仅确认完整性分母，一致性指标分母仍未决定。"
                        + "是否插补、适用指标和方法均未决定，本次保持缺失状态并说明不同处理的影响。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付指标表和方法步骤。\n"
                        + "是否插补、适用指标和方法均未决定，本次保持缺失状态并说明不同处理的影响。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得公开记录标识。")),
                "test", "test", false, List.of());
        var result = new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "输出", "结果可核对", "示例"),
                List.of(), answers, true, List.of("不得编造数据"), false, 1, RAW);
        assertThat(occurrences(result.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(1);
        assertThat(result.optimizedPrompt()).contains("已确认决定：完整性指标分母采用所有有效出院记录",
                "仅确认完整性分母，一致性指标分母仍未决定", "交付指标表和方法步骤");
    }

    @Test
    void compactsTheGlobalLayoutIntroAndIndependentImputationBanButPreservesOtherBusinessScope() {
        String task = "制定数据质量研究的方法方案，围绕缺失与一致性问题，按以下部分组织：\n"
                + "1. 生成指标表。\n" + IMPUTATION;
        var response = providerWithTask(task, "不得默认统一插补；是否插补、适用指标和方法均未决定，本次保持缺失状态并说明不同处理的影响。");
        var answers = List.of(new PlanAnswer("imputation", "是否对缺失病例使用插补？", "暂不确定。" + IMPUTATION));
        var result = new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "输出", "结果可核对", "示例"),
                List.of(), answers, true, List.of("不得编造数据"), false, 1, RAW);
        assertThat(occurrences(result.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(1);
        assertThat(result.optimizedPrompt()).contains("不得默认统一插补");
        var scoped = providerWithTask("制定A医院的方案，按以下部分组织：\n" + IMPUTATION, "忠于资料。");
        var scopedResult = new OptimizationResultAssembler().assemble(scoped,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "输出", "结果可核对", "示例"),
                List.of(), answers, true, List.of("不得编造数据"), false, 1, RAW);
        assertThat(occurrences(scopedResult.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(2);
    }

    @Test
    void removesDemonstrativeStateRestatementsOnlyWithinTheBoundNamedItem() {
        var answers = List.of(new PlanAnswer("complete", "完整性指标分母如何定义？",
                        "完整性指标分母采用所有有效出院记录。仅确认完整性分母，一致性指标分母仍未决定。"),
                new PlanAnswer("consistency", "跨字段逻辑一致性指标的分母应如何定义？",
                        "暂不确定。跨字段一致性指标的分母尚未决定，不能直接继承完整性指标分母；后续须按各逻辑规则的适用记录核实。"),
                new PlanAnswer("coverage", "A医院2022年的数据覆盖度如何？",
                        "暂不确定。A医院2022年覆盖度尚未核实，其他年份与医院不能替该项建立覆盖事实。"));
        var result = assemble("病案方法方案。", "忠于资料。", List.of(
                "跨字段逻辑一致性指标的分母应如何定义？该分母尚未决定。",
                "A医院2022年的数据覆盖度如何？该覆盖度尚未核实，其他年份与医院不能替该项建立覆盖事实。"),
                answers, true, RAW);
        assertThat(result.ambiguities()).hasSize(2);
        assertThat(result.optimizedPrompt()).doesNotContain("补充说明：跨字段逻辑一致性指标的分母应如何定义", "该分母尚未决定", "该覆盖度尚未核实");
        assertThat(occurrences(result.optimizedPrompt(), "其他年份与医院不能替该项建立覆盖事实")).isEqualTo(1);
        var different = assemble("病案方法方案。", "忠于资料。", List.of(
                "A医院2022年的数据覆盖度如何？B医院2022年覆盖度尚未核实。",
                "跨字段逻辑一致性指标的分母应如何定义？该分母尚未决定。若缺失率>20%，必须单独分析。"),
                answers, true, RAW);
        assertThat(different.optimizedPrompt()).contains("B医院2022年覆盖度尚未核实", "若缺失率>20%，必须单独分析");
    }

    @Test
    void retainsThePartialConfirmationBoundaryAndMovesOnlyTheCoveredConsistencyTail() {
        var answers = List.of(new PlanAnswer("complete", "完整性指标分母如何定义？",
                        "完整性指标分母采用所有有效出院记录。仅确认完整性分母，一致性指标分母仍未决定。"),
                new PlanAnswer("consistency", "跨字段一致性指标分母如何定义？",
                        "暂不确定。跨字段一致性指标的分母尚未决定，不能直接继承完整性指标分母；后续须按各逻辑规则的适用记录核实。"));
        String actualAndPending = "完整性指标分母采用所有有效出院记录。仅确认完整性分母，一致性指标分母仍未决定，"
                + "不能直接继承完整性指标分母；后续须按各逻辑规则的适用记录核实。";
        var result = assemble("病案方法方案。", actualAndPending, List.of(), answers, true, RAW);
        assertThat(result.optimizedPrompt()).contains("完整性指标分母采用所有有效出院记录", "仅确认完整性分母");
        assertThat(occurrences(result.optimizedPrompt(), "不能直接继承完整性指标分母")).isEqualTo(1);
        assertThat(occurrences(result.optimizedPrompt(), "后续须按各逻辑规则的适用记录核实")).isEqualTo(1);
        var newObject = assemble("病案方法方案。", "仅确认完整性分母，新生儿一致性指标分母仍未决定，需另行核实。",
                List.of(), answers, true, RAW);
        assertThat(newObject.optimizedPrompt()).contains("新生儿一致性指标分母仍未决定，需另行核实");
    }

    @Test
    void compactsTheSemicolonJoinedPendingTailWithoutRemovingTheActualDenominator() {
        var answers = List.of(new PlanAnswer("complete", "完整性指标分母如何定义？",
                        "完整性指标分母采用所有有效出院记录。仅确认完整性分母，一致性指标分母仍未决定。"),
                new PlanAnswer("consistency", "跨字段一致性指标分母如何定义？",
                        "暂不确定。跨字段一致性指标的分母尚未决定，不能直接继承完整性指标分母；后续须按各逻辑规则的适用记录核实。"));
        String mixed = "完整性指标分母采用所有有效出院记录（包括相应字段缺失的记录），有效是指去重后满足研究时间和对象范围的记录，"
                + "不因该字段缺失而排除；仅确认完整性分母，一致性指标分母仍未决定，不能直接继承完整性指标分母。";
        var result = assemble("病案方法方案。", mixed, List.of(), answers, true, RAW);
        assertThat(occurrences(result.optimizedPrompt(), "不能直接继承完整性指标分母")).isEqualTo(1);
        assertThat(result.optimizedPrompt()).contains("有效是指去重后满足研究时间和对象范围的记录", "不因该字段缺失而排除", "仅确认完整性分母");
        var different = assemble("病案方法方案。", "完整性指标分母采用A医院的有效记录；仅确认完整性分母，一致性指标分母仍未决定。",
                List.of(), answers, true, RAW);
        assertThat(different.optimizedPrompt()).contains("完整性指标分母采用A医院的有效记录；仅确认完整性分母，一致性指标分母仍未决定");
    }

    @Test
    void compactsOnlyTheExplicitBackgroundStatusTailAndPreservesMaterialAndOtherScopes() {
        var answers = List.of(new PlanAnswer("imputation", "是否对缺失病例使用插补？", "暂不确定。" + IMPUTATION));
        for (String prefix : List.of("", "A医院：\n", "> ")) {
            String background = "研究基于真实已提供的病案数据字典。\n" + prefix
                    + "- 未决：A医院2022年覆盖度尚未核实；" + IMPUTATION;
            var result = assemble(background, "忠于资料。", List.of(), answers, true, RAW);
            assertThat(result.optimizedPrompt()).contains("研究基于真实已提供的病案数据字典", "A医院2022年覆盖度尚未核实");
            assertThat(occurrences(result.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响"))
                    .isEqualTo(prefix.isEmpty() ? 1 : 2);
        }
    }

    @Test
    void mergesSameDenominatorReferenceAndOriginalQuestionFieldExpansionWithoutLosingNewScope() {
        var answers = List.of(new PlanAnswer("denominator", "缺失记录是否进入各类质量指标的分母？",
                        "完整性指标分母采用所有有效出院记录，包括相应字段缺失的记录。仅确认完整性分母，一致性指标分母仍未决定。"),
                new PlanAnswer("imputation", "对缺失病例是否采用插补？若采用，哪些指标或字段允许插补？", "暂不确定。" + IMPUTATION));
        String denominator = "一致性指标分母尚未决定：完整性指标分母已确认为所有有效出院记录（包括相应字段缺失的记录），"
                + "但跨字段一致性指标的分母是否采用相同口径仍未确定，会影响指标计算与年度/医院比较结果。";
        String expansion = "是否对缺失病例采用插补、适用哪些指标或字段、采用何种方法均未决定；" + IMPUTATION.substring(IMPUTATION.indexOf('，') + 1);
        var decisions = ConfirmedDecisionSet.from(answers);
        var pending = decisions.pendingDecisions().stream().filter(part -> part.questionId().equals("imputation")).findFirst().orElseThrow();
        assertThat(new PendingReminderIdentity(decisions, RAW).imputationFieldExpansion(expansion, pending)).isPresent();
        assertThat(new PlanAmbiguityMerger(decisions, RAW).merge(List.of(denominator, expansion), List.of(), List.of()).executionPrerequisites())
                .anyMatch(value -> value.contains("若采用插补，适用哪些指标或字段尚未决定"));
        var result = assemble("病案方法方案。", "忠于资料。", List.of(denominator, expansion), answers, true, RAW);
        assertThat(result.ambiguities()).hasSize(2);
        assertThat(result.optimizedPrompt()).contains("若采用插补，适用哪些指标或字段尚未决定", "会影响指标计算与年度/医院比较结果");
        assertThat(occurrences(result.optimizedPrompt(), "本次保持缺失状态并说明不同处理的影响")).isEqualTo(1);
        var different = assemble("病案方法方案。", "忠于资料。", List.of(expansion.replace("缺失病例", "新生儿缺失病例"),
                "新生儿跨字段一致性指标的分母是否采用相同口径仍未确定。若缺失率>20%，必须单独分析。"), answers, true, RAW);
        assertThat(different.optimizedPrompt()).contains("新生儿缺失病例", "新生儿跨字段一致性指标", "若缺失率>20%，必须单独分析");
        var unboundFields = assemble("病案方法方案。", "忠于资料。", List.of(expansion),
                List.of(new PlanAnswer("imputation", "是否对缺失病例使用插补？", "暂不确定。" + IMPUTATION)), true, RAW);
        assertThat(unboundFields.ambiguities()).hasSize(2);
        assertThat(unboundFields.optimizedPrompt()).contains("适用哪些指标或字段");
    }

    /** 构造真实组装边界输入，不让测试工具在输出后修正文。 */
    private EnhancementProviderResponse providerWithTask(String task, String constraints) {
        return new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "病案方法方案。"),
                new PromptSection(PromptSectionType.TASK, "任务", task),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付指标表和方法步骤。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints)),
                "test", "test", false, List.of());
    }

    /** 经过真实结果组装路径，检查提醒、复制正文和已确认信息是否同步。 */
    private OptimizationResult assemble(String background, String constraints, List<String> findings,
                                        List<PlanAnswer> answers, boolean confirmed, String raw) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", background),
                new PromptSection(PromptSectionType.TASK, "任务", "制定病案数据质量方法方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付指标表和方法步骤。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints)), "test", "test", false, findings);
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "输出", "结果可核对", "示例"),
                List.of(), answers, confirmed, List.of("不得编造数据"), false, 1, raw);
    }

    private long occurrences(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1L;
    }
}
