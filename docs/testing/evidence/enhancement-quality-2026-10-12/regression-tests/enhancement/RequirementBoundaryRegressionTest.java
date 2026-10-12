package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 复现真实模型在任务段新增数据例外、收紧检验选择和越界采用标准人口的症状。
 * 合法候选、用户明确选值和引文同时验证，避免修复退化为一律禁止专业建议。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RequirementBoundaryRegressionTest {
    private static final String DATA = "把审批规则整理为开发提示词，不得使用假设数据。";
    private static final String TEST_SELECTION = "为研究生准备论文方法部分的写作提示词。具体检验依据未来数据分布选择，不预设必然显著。";
    private final OptimizationResultAssembler assembler = new OptimizationResultAssembler();

    @Test
    void observedPlanQuestionAnswerWorkflowCannotBecomeModelSelfPlanning() {
        var contract = com.promptoptimizer.provider.domain.RequirementBoundaryContract.from(
                "撰写研究设计，研究Plan问答对提示词质量的影响。", List.of(), List.of());
        assertThatThrownBy(() -> contract.validate(
                "Plan组为在原始提示词中先要求模型生成执行计划再据此作答；之后不能改变用户目标。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThat(contract.guidance()).contains("用户回答", "优化提示词");
    }

    @Test
    void actualQuestionAnswerWorkflowAndExplicitSelfPlanningRemainDistinct() {
        var questions = com.promptoptimizer.provider.domain.RequirementBoundaryContract.from(
                "比较先让用户回答问题再增强提示词与直接增强。", List.of(), List.of());
        org.assertj.core.api.Assertions.assertThatCode(() -> questions.validate(
                "Plan组由增强器提问，用户回答后再增强提示词；不能改为模型先生成计划。", "sections.TASK"))
                .doesNotThrowAnyException();
        var plans = com.promptoptimizer.provider.domain.RequirementBoundaryContract.from(
                "研究先让模型生成执行计划再作答是否有效。", List.of(), List.of());
        org.assertj.core.api.Assertions.assertThatCode(() -> plans.validate(
                "Plan组先要求模型生成执行计划再据此作答。", "sections.TASK"))
                .doesNotThrowAnyException();
    }

    @Test
    void observedNegativeAcceptanceDoesNotBecomeDataPermission() {
        var contract = com.promptoptimizer.provider.domain.RequirementBoundaryContract.from(DATA, List.of(), List.of());
        org.assertj.core.api.Assertions.assertThatCode(() -> contract.validate(
                "未编造效应量、P值或结论，未使用假设数据或虚构数据。", "sections.ACCEPTANCE"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.validate("未使用假设数据，但可以使用虚构数据验证。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordedDataExceptionInTaskIsRejectedRegardlessOfExampleSwitch(boolean examples) {
        assertConflict(DATA, "要求下游执行者不得使用假设数据；若需要测试或验证，应使用真实数据或明确标注为示例的虚构数据，但不得将示例数据作为正式规则依据。",
                List.of(), examples);
    }

    @ParameterizedTest
    @ValueSource(strings = {"可使用标注为虚构的数据做验证。", "可以构造模拟数据，只用于测试。", "假设数据可以使用，但不能作为正式依据。"})
    void labelingOrTestingDoesNotCreateADataException(String task) {
        assertConflict(DATA, task, List.of(), false);
    }

    @Test
    void englishDataProhibitionAlsoRejectsAnException() {
        assertConflict("Prepare a development prompt. Do not use hypothetical data.",
                "You may use clearly labeled synthetic data for validation.", List.of(), false);
    }

    @Test
    void coordinatedDataProhibitionDoesNotLoseItsScopeOnAnotherEnhancement() {
        assertConflict("整理开发提示词，不得使用假设、虚构或模拟数据。", "可以使用虚构数据验证。", List.of(), false);
    }

    @Test
    void anExampleReferenceDoesNotBecomeACurrentInstructionOrDataPermission() {
        var result = result(DATA, "不得使用真实或虚构数据替代审批依据。\n> 旧错误：允许虚构数据。\n```text\n可以使用虚构数据\n```", List.of(), false);
        assertThat(result.optimizedPrompt()).contains("不得使用假设数据");
    }

    @Test
    void scopedBanOnFabricatingResultsDoesNotBanLabeledDemonstration() {
        var result = result("准备论文方法写作提示词，不得编造效应量或P值。", "示例使用明确标注的虚构演示数据，不作为研究结果。", List.of(), true);
        assertThat(result.optimizedPrompt()).contains("虚构演示");
    }

    @ParameterizedTest
    @ValueSource(strings = {"在涉及统计检验时，只写依据未来数据分布选择具体检验方法，不写具体检验名称。", "不得列出任何统计检验名称。"})
    void conditionalTestSelectionDoesNotAuthorizeABlanketNamingBan(String task) {
        assertConflict(TEST_SELECTION, task, List.of(), true);
    }

    @Test
    void conditionalCandidatesRemainUsefulAndDoNotAssertSelectionOrSignificance() {
        var result = result(TEST_SELECTION, "依据未来数据分布选择具体检验，可说明t检验或非参数检验等候选及适用前提，不提前选定，不预设显著。", List.of(), true);
        assertThat(result.optimizedPrompt()).contains("t检验", "候选").doesNotContain("不写具体检验名称");
    }

    @Test
    void anExplicitUserNamingBanIsPreserved() {
        var result = result(TEST_SELECTION + "不得列出具体检验名称。", "依据未来数据分布选择检验，但不得列出具体检验名称。", List.of(), false);
        assertThat(result.optimizedPrompt()).contains("不得列出具体检验名称");
    }

    @Test
    void methodOnlyConfirmationCannotSelectTheStandardPopulation() {
        assertConflict("撰写死亡率特征分析报告。", "死亡率采用年龄标准化，本文采用2010年中国人口普查人口作为标准人口。", ageOnly(), false);
    }

    @Test
    void futureConfirmationDoesNotAllowATemporaryPopulationDefault() {
        assertConflict("撰写死亡率特征分析报告。", "标准人口暂按2010年中国人口普查人口，之后再确认。", ageOnly(), false);
    }

    @Test
    void standardPopulationCandidatesAreNotSilencedAndOtherReportContentContinues() {
        var result = result("撰写死亡率特征分析报告。", "采用年龄标准化，可考虑2010年中国人口普查人口作为标准人口，具体标准人口仍待确认，报告的其余内容继续撰写。", ageOnly(), false);
        assertThat(result.optimizedPrompt()).contains("2010", "待确认", "继续撰写");
    }

    @Test
    void explicitlySelectedPopulationDoesNotBecomeUnknown() {
        var answers = new ArrayList<>(ageOnly());
        answers.add(new PlanAnswer("population", "年龄标准化的标准人口采用哪一版？", "采用2010年中国人口普查人口作为标准人口。"));
        var result = result("撰写死亡率特征分析报告。", "本文采用2010年中国人口普查人口作为标准人口。", answers, false);
        assertThat(result.optimizedPrompt()).contains("2010年中国人口普查人口");
    }

    @Test
    void anotherPopulationVersionCannotBorrowTheConfirmation() {
        var answers = new ArrayList<>(ageOnly());
        answers.add(new PlanAnswer("population", "年龄标准化的标准人口采用哪一版？", "采用2010年中国人口普查人口作为标准人口。"));
        assertConflict("撰写死亡率特征分析报告。", "本文采用2020年中国人口普查人口作为标准人口。", answers, false);
    }

    @Test
    void copiedPromptCarriesThePositiveSelectionConditionAndIndependentParameterBoundary() {
        var result = result(TEST_SELECTION + "死亡率比较采用年龄标准化。", "按原始要求准备写作提示词。", List.of(), true);
        assertThat(result.optimizedPrompt()).contains("候选检验", "标准人口");
        assertThat(result.optimizedPrompt()).doesNotContain("禁止写检验名称", "先补齐全部");
    }

    @Test
    void mentioningAMethodOrAskingWhetherToUseItDoesNotConfirmIt() {
        var view = EffectiveTaskViewFactory.create("解释年龄标准化，是否采用年龄标准化仍待确认？", TemplateCode.GENERAL, List.of(), List.of(), false);
        assertThat(view.requirementBoundaries().guidance()).doesNotContain("方法已确定");
    }

    @Test
    void aQuestionOnlyPlanAnswerDoesNotConfirmAMethod() {
        var view = EffectiveTaskViewFactory.create("撰写报告。", TemplateCode.GENERAL,
                List.of(new PlanAnswer("method", "死亡率比较采用哪种方法？", "是否采用年龄标准化仍待确认？")), List.of(), false);
        assertThat(view.requirementBoundaries().guidance()).doesNotContain("方法已确定");
    }

    @Test
    void equivalentBoundaryInstructionsDoNotAcquireAnotherFullCopy() {
        var view = EffectiveTaskViewFactory.create(TEST_SELECTION + "死亡率比较采用年龄标准化。不得使用假设数据。",
                TemplateCode.GENERAL, List.of(), List.of(), false);
        assertThat(view.requirementBoundaries().missingGuidance("不得使用假设数据，禁令适用于所有用途，包括测试和验证。\n具体检验依据未来数据分布选择，可说明候选检验名称和适用条件。\n"
                + "年龄标准化仅确认方法类别，不能宣称直接或间接标准化方式也已确定；可解释候选方式和适用条件。\n"
                + "年龄标准化所用标准人口的来源和年份未确定，不默认采用某年人口。" )).isEmpty();
    }

    @Test
    void recordedDownstreamClaimDoesNotFollowFromTheParentMethodConfirmation() {
        assertConflict("撰写死亡率特征分析报告。", "年龄标准化方法在本研究中已确定为直接标准化，但标准人口的年份和来源尚未选定。", ageOnly(), false);
    }

    @Test
    void aBranchCandidateMayBeExplainedWithoutInventingConfirmation() {
        var result = result("撰写死亡率特征分析报告。", "采用年龄标准化，可解释直接标准化和间接标准化的适用条件，不将候选写成已确认。", ageOnly(), false);
        assertThat(result.optimizedPrompt()).contains("直接标准化和间接标准化的适用条件");
    }

    @Test
    void theUserCanActuallyChooseTheConcreteStandardizationBranch() {
        var answers = List.of(new PlanAnswer("method", "死亡率比较采用哪种方法？", "采用直接年龄标准化。"));
        var result = result("撰写死亡率特征分析报告。", "本研究已确定为直接年龄标准化。", answers, false);
        assertThat(result.optimizedPrompt()).contains("直接年龄标准化");
    }

    @Test
    void futurePopulationSelectionIsNotAnIndependentCurrentParameterConfirmation() {
        assertConflict("撰写报告；若以后确认采用2010年中国人口普查人口作为标准人口，再计算标化率。",
                "本文采用2010年中国人口普查人口作为标准人口。", ageOnly(), false);
    }

    @Test
    void futureBranchSelectionDoesNotBecomeACurrentConfirmedMethod() {
        assertConflict("撰写报告；若以后确认采用直接年龄标准化，再执行该分支。",
                "年龄标准化方法已确定为直接标准化。", ageOnly(), false);
    }

    @Test
    void fictionBanDoesNotAuthorizeABanOnAllExampleData() {
        assertConflict(DATA, "不得使用假设数据、虚构数据或示例数据。", List.of(), false);
    }

    @Test
    void aRealExampleDataBanIsValidWhenTheUserActuallyRequiresIt() {
        var result = result(DATA + "不得使用示例数据。", "不得使用假设数据或示例数据。", List.of(), false);
        assertThat(result.optimizedPrompt()).contains("不得使用示例数据");
    }

    @Test
    void copiedDataProhibitionStatesAllUsesInsteadOfOnlyNotPassingExamplesOffAsFacts() {
        var result = result(DATA, "不得使用假设数据来替代真实业务数据。", List.of(), false);
        assertThat(result.optimizedPrompt()).contains("所有用途", "测试和验证", "不得缩为仅禁止冒充真实业务数据");
    }

    private List<PlanAnswer> ageOnly() {
        return List.of(new PlanAnswer("method", "死亡率比较采用粗率还是年龄标准化？", "死亡率比较采用年龄标准化。"));
    }

    private void assertConflict(String raw, String task, List<PlanAnswer> answers, boolean examples) {
        assertThatThrownBy(() -> result(raw, task, answers, examples))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class,
                        failure -> assertThat(failure.getReason()).isNotNull());
    }

    private com.promptoptimizer.enhancement.domain.OptimizationResult result(String raw, String task, List<PlanAnswer> answers, boolean examples) {
        if (raw.contains("提示词") || raw.contains("prompt")) task = "生成原定写作或开发提示词（requested prompt）。\n" + task;
        var sections = List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", "保留当前研究或开发目标。"),
                new PromptSection(PromptSectionType.TASK, "任务", task),
                new PromptSection(PromptSectionType.OUTPUT, "输出", raw.contains("报告") ? "交付报告正文。" : "交付可直接使用的写作或开发提示词。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "保留原始需求的限制。"));
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), "SUCCESS", List.of(), List.of(), List.of(), "v1");
        return assembler.assemble(new EnhancementProviderResponse(sections, "test", "test-model", false, List.of()),
                context, new PromptTemplate(TemplateCode.GENERAL, "交付原定内容。", "符合原定要求。", "示例。"),
                List.of(), answers, !answers.isEmpty(), List.of(), examples, 1, raw);
    }
}
