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
 * 回放实际作品误用金额零值API的交付缺口；保护用户语言版本、比较边界与只交付方案的范围。
 * 代码真正能否编译仍由合成作品独立验收，不把正文中的“可编译”当成编译证据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class JavaCodeDeliveryRegressionTest {
    private static final String RAW = "请使用Java21和BigDecimal实现配送费与服务费两个计算方法，交付完整可编译代码及独立测试类。"
            + "不修改项目文件。配送费amount>200免收，否则12；服务费0<amount<=100收2，amount>100收5。"
            + "返回金额保留2位小数；null与负数抛IllegalArgumentException。";

    @Test
    void carriesRealDecimalApisAndCompleteFilesIntoTheCopyableOutput() {
        var result = assemble(RAW);
        assertThat(result.optimizedPrompt()).contains("Java21交付", "BigDecimal.ZERO", "compareTo", "独立测试类")
                .doesNotContain("BigDecimal.zero()");
        assertThat(result.sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT)
                .findFirst().orElseThrow().content()).contains("BigDecimal.ZERO");
    }

    @Test
    void keepsBothObjectsAndTheOriginalComparisonScaleAndException() {
        String result = assemble(RAW).optimizedPrompt();
        assertThat(result).contains("amount>200", "amount<=100", "amount>100", "2位小数", "IllegalArgumentException");
    }

    @Test
    void makesIndependentTestImportsAndStaticMethodReferencesExplicit() {
        String output = assemble(RAW).sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT)
                .findFirst().orElseThrow().content();
        assertThat(output).contains("每个独立文件各自包含import", "目标类名限定或合法static import");
    }

    @Test
    void checksTheWholeFileInventoryInsteadOfStoppingAfterTheImplementation() {
        String output = assemble(RAW).sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT)
                .findFirst().orElseThrow().content();
        assertThat(output).contains("逐文件交付完整代码", "不能只输出实现类而遗漏测试类");
    }

    @Test
    void doesNotApplyJavaImplementationRequirementsToTranslationNewsOrAnExplanation() {
        for (String raw : List.of("把以下中文翻译成英文，只输出译文：Java21和BigDecimal代码可编译。",
                "撰写新闻稿，介绍Java21和BigDecimal课程，不提供代码。",
                "Java21的BigDecimal代码可编译，这是一条背景资料，请概述其含义。",
                "只解释Java21的BigDecimal比较方法，不输出代码。")) {
            assertThat(assemble(raw).optimizedPrompt()).doesNotContain("Java21交付");
        }
    }

    @Test
    void neverUpgradesJava17OrInjectsAFrameworkForAPureCalculator() {
        assertThat(assemble(RAW.replace("Java21", "Java17")).optimizedPrompt()).doesNotContain("Java21交付");
        String guide = assemble(RAW).sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT)
                .findFirst().orElseThrow().content();
        assertThat(guide).doesNotContain("Spring", "Mapper", "数据库", "JPA", "Redis");
    }

    @Test
    void doesNotForceAnUnrequestedTestOrInventAMonetaryScale() {
        String result = assemble("用Java21的BigDecimal实现两个数值的比较，只交付完整可编译代码。")
                .sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT)
                .findFirst().orElseThrow().content();
        assertThat(result).contains("Java21交付").doesNotContain("独立测试类", "2位", "100", "200");
    }

    private static OptimizationResult assemble(String raw) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "基于用户本次资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", raw),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付用户所要求的完整文件。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "遵守本次范围和输入边界。")),
                "test", "test", false, List.of());
        return new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.GENERAL, "交付原定文件。", "核对原定范围。", "示例"),
                List.of(), List.of(), false, List.of("不得泄露凭据。"), false, 1, raw, List.of(), List.of());
    }
}
