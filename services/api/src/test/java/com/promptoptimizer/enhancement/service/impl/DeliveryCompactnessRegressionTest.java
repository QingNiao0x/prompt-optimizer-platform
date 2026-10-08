package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放权威交付说明被多段复写的实际症状，保护计数范围、独立业务作用域与表格代码。
 * 只检查可证明的等价说明，不要求任意语义相近的规则被删除。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DeliveryCompactnessRegressionTest {
    private static final String RAW = "制定数据质量方法方案，交付规则表、指标表和必要伪代码。"
            + "正文1500至2000字，指标表另计，标题不计。完整性指标分母尚未确定。";

    @Test
    void aCompleteBoldInstructionIsNotABusinessHeadingAndAppearsOnceInCopiedDelivery() {
        String rule = "不同指标的分母或阈值分别命名，禁止用同一个通用变量或共同分母覆盖未决指标。";
        for (String marker : List.of("**", "__")) {
            var result = assemble(RAW, "交付物：\n" + marker + rule + marker + "\n交付必要伪代码。",
                    "不得编造数据。", "核对指标状态。");
            assertThat(result.optimizedPrompt().split(java.util.regex.Pattern.quote(rule), -1)).as(marker).hasSize(2);
            assertThat(result.optimizedPrompt()).contains("完整性指标", "待确认", "指标表", "必要伪代码");
        }
    }

    @Test
    void boldBusinessScopesQuotesCodeAndNewConditionsRemainIntact() {
        String rule = "不同指标的分母或阈值分别命名，禁止用同一个通用变量或共同分母覆盖未决指标。";
        for (String original : List.of("**甲院2026年新增项目**\n**" + rule + "**",
                "### 乙院2026年\n__" + rule + "__", "~~~text\n**" + rule + "**\n~~~",
                "> **" + rule + "**", "**" + rule + "新增退款指标仍需独立审批。**")) {
            assertThat(assemble(RAW, original, "不得编造数据。", "核对独立参数。").optimizedPrompt())
                    .as(original).contains(original);
        }
    }

    @Test
    void keepsOneAuthoritativeDeliveryExplanationAcrossExecutionSections() {
        String guidance = UnresolvedDecisionContract.DELIVERY_GUIDANCE;
        var result = assemble(RAW, "交付规则表与指标表。" + guidance,
                "平台强制约束（不得删除或弱化）：\n不得编造数据。" + guidance, "核对交付完整。" + guidance);
        String sentence = "不同指标的分母或阈值分别命名，禁止用同一个通用变量或共同分母覆盖未决指标。";
        assertThat(result.optimizedPrompt().split(java.util.regex.Pattern.quote(sentence), -1)).hasSize(2);
        assertThat(result.optimizedPrompt()).contains("完整性指标", "待确认", "指标表", "必要伪代码");
    }

    @Test
    void neverCompactsCodeTablesQuotesOrAnotherBusinessScopeAsPlatformGuidance() {
        String sentence = "不同指标的分母或阈值分别命名，禁止用同一个通用变量或共同分母覆盖未决指标。";
        String protectedContent = "```text\n" + sentence + "\n```\n"
                + "| 说明 |\n| --- |\n| " + sentence + " |\n> " + sentence + "\n"
                + "### 乙院2026年新增项目\n" + sentence + "\n乙院2026年分母尚未核实。";
        var result = assemble(RAW, protectedContent, "不得编造数据。", "逐项核对业务边界。");
        assertThat(result.optimizedPrompt()).contains(protectedContent);
    }

    @Test
    void preservesAChangedConditionOrAnotherYearEvenWhenTheSentenceStartsLikeGuidance() {
        String extended = "不同指标的分母或阈值分别命名，禁止用同一个通用变量或共同分母覆盖未决指标；甲院2026年新增的观察指标另行审批。";
        var result = assemble(RAW, "交付原定方法方案。" + extended, "不得编造数据。", "核对独立参数。");
        assertThat(result.optimizedPrompt()).contains(extended, "当前参数依据", "完整性指标");
    }

    @Test
    void carriesTheOriginalBodyLengthAndSeparateAttachmentsIntoAConcisePlan() {
        var result = assemble(RAW, "交付正文、规则表和指标表。", "不得虚构结果。", "核对统计口径。");
        assertThat(result.optimizedPrompt()).contains("正文篇幅", "1500–2000字", "1750字", "附表")
                .contains("不编造", "指标表", "只有用户明确另计")
                .doesNotContain("必须分成", "必须写成");
    }

    @Test
    void doesNotBudgetAWholeNarrativeBeforeRequiredTablesAndPseudocode() {
        assertThat(TaskBodyLengthContract.guidance(RAW)).contains("1500–2000字", "1750字")
                .doesNotContain("个自然段", "每段约");
        assertThat(TaskBodyLengthContract.guidance("撰写科研讨论正文1500至2000字，不包含表格或代码。"))
                .contains("个自然段", "组织建议");
    }

    @Test
    void preservesAnExplicitParagraphCountAndNeverTurnsTheSuggestionIntoARequirement() {
        String raw = "科研方法方案正文1500至2000字，固定3段，指标表另计。";
        String guidance = TaskBodyLengthContract.guidance(raw);
        assertThat(guidance).contains("1500–2000字", "计数边界").doesNotContain("个自然段", "新增固定段数");
    }

    @Test
    void doesNotInventBodyLengthOrOverrideJsonOrConditionalExamples() {
        for (String raw : List.of("将数据方案整理为纯JSON，示例正文1500至2000字。",
                "制定数据方案，正文1000至1500字或1800至2000字，暂未选择。",
                "如果以后确定正文1500至2000字，再安排篇幅。",
                "交付研究摘要200至300字和正文，篇幅待定。")) {
            assertThat(assemble(raw, "交付原定格式。", "不编造数据。", "保持业务边界。").optimizedPrompt())
                    .doesNotContain("正文篇幅：", "1750字起草");
        }
    }

    @Test
    void doesNotApplyQuotedLengthToTranslationOrANegatedBodyRequest() {
        assertThat(TaskBodyLengthContract.guidance("请将下面的文字译成英文：正文1500至2000字，指标表另计。"))
                .isEmpty();
        assertThat(TaskBodyLengthContract.guidance("不要输出正文1500至2000字，只给研究提纲。"))
                .isEmpty();
    }

    @Test
    void preservesIndicatorTableAndExplainsThatCountsNeedNoInventedDenominator() {
        var result = assemble(RAW, "交付指标表，包含异常计数与错误率。", "不得编造数据。", "核对状态。");
        assertThat(result.optimizedPrompt()).contains("计数指标", "不适用", "指标表", "口径依据", "待确认");
    }

    @Test
    void copiedPromptCarriesTheEvidenceBoundaryInsteadOfLeavingItOnlyInTheEnhancerInstructions() {
        var result = assemble(RAW + "列出清洗规则，同一编号保留已确认的最新记录。",
                "交付清洗规则、指标表和必要伪代码。", "不得编造数据。", "核对业务边界。");
        assertThat(result.optimizedPrompt()).contains("未给出时不能默认负值归零", "版本最大值", "已明确的规则原样执行");
    }

    @Test
    void inputParameterBasisDoesNotDemandAnotherDuplicateDeliverable() {
        var result = assemble(RAW, "交付规则表和指标表。", "不得编造数据。", "核对交付完整。");
        assertThat(result.optimizedPrompt()).contains("当前参数依据", "合入最终指标表", "用户明确要求", "不再重复交付");
    }

    @Test
    void genericDeliveryHeadingsAndInlineFormattingDoNotHideAnIdenticalPlatformRule() {
        String rule = "每个独立文件各自包含import，不能借用实现文件的导入。";
        var sections = new java.util.EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出",
                "交付物：\n- 每个独立文件各自包含 `import`，不能借用实现文件的导入。\n" + rule));
        AuthoritativeDeliveryCompactor.compact(sections, java.util.Map.of(PromptSectionType.OUTPUT, rule));
        assertThat(sections.get(PromptSectionType.OUTPUT).content().split("不能借用实现文件的导入", -1)).hasSize(2);
    }

    @Test
    void currentJavaFileAndTestHeadingsDoNotStartAnotherBusinessScope() {
        String rule = "每个独立文件各自包含import，不能借用实现文件的导入。";
        var sections = new java.util.EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        String delivery = "交付以下文件：\n1. FeeCalculator.java：完整实现。\n2. FeeCalculatorTest.java：独立测试类。\n"
                + "测试覆盖：\n- 负数、null、0和阈值上下边界。\n" + rule + "\n" + rule;
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", delivery));
        AuthoritativeDeliveryCompactor.compact(sections, java.util.Map.of(PromptSectionType.OUTPUT, rule));
        assertThat(sections.get(PromptSectionType.OUTPUT).content().split("不能借用实现文件的导入", -1)).hasSize(2);
        assertThat(sections.get(PromptSectionType.OUTPUT).content()).contains("FeeCalculator.java", "FeeCalculatorTest.java",
                "负数、null、0和阈值上下边界");
    }

    @Test
    void genericHeadingsCannotReleaseAnExistingNamedBusinessScope() {
        String rule = "不同指标的分母或阈值分别命名，禁止用同一个通用变量或共同分母覆盖未决指标。";
        String business = "### 甲院2026年\n交付物：\n" + rule + "\n交付以下文件：\n" + rule + "\n测试覆盖：\n" + rule
                + "\n当前参数依据（用于生成指标表，不代替指标表）：\n" + rule;
        var sections = new java.util.EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", rule + "\n" + business));
        AuthoritativeDeliveryCompactor.compact(sections, java.util.Map.of(PromptSectionType.OUTPUT, rule));
        assertThat(sections.get(PromptSectionType.OUTPUT).content()).contains(business);
    }

    private static OptimizationResult assemble(String raw, String output, String constraints, String acceptance) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "基于用户提供的数据资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", "制定可核查的方法方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints),
                new PromptSection(PromptSectionType.ACCEPTANCE, "验收标准", acceptance)),
                "test", "test", false, List.of());
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付方法方案。", "核对口径。", "示例"),
                List.of(), List.of(), false, List.of("不得泄露凭据。"), false, 1, raw, List.of(), List.of());
    }
}
