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
        assertThat(view.requirementBoundaries().missingGuidance("不得使用假设数据。\n具体检验依据未来数据分布选择，可说明候选检验名称和适用条件。\n"
                + "年龄标准化所用标准人口的来源和年份未确定，不默认采用某年人口。" )).isEmpty();
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
