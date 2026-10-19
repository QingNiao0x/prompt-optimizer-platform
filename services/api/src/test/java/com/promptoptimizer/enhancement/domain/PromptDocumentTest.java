package com.promptoptimizer.enhancement.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 五模块投影不得丢失旧链路的独立未决条件或示例交付要求。 */
class PromptDocumentTest {
    @Test
    void fiveModulesRetainEveryOriginalSectionIncludingClarificationsAndExamples() {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        var original = new OptimizationResult("既有正文", List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "已提供的项目事实"),
                new PromptSection(PromptSectionType.TASK, "任务", "保持原任务目标"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付原定成品"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "独立条件和否定"),
                new PromptSection(PromptSectionType.ACCEPTANCE, "验收", "可检查的交付要求"),
                new PromptSection(PromptSectionType.CLARIFICATIONS, "澄清", "数据缺口只限制依赖该数据的计算"),
                new PromptSection(PromptSectionType.EXAMPLES, "示例", "用户明确要求的输入输出示例")),
                context, List.of(), List.of(), TemplateCode.GENERAL, new ProviderMetadata("mock", "fixture", true), 1);
        var result = PromptDocument.structured(original);
        assertThat(result.sections()).extracting(PromptSection::type).containsExactly(
                PromptSectionType.BACKGROUND, PromptSectionType.TASK, PromptSectionType.OUTPUT,
                PromptSectionType.CONSTRAINTS, PromptSectionType.ACCEPTANCE);
        original.sections().forEach(section -> assertThat(result.optimizedPrompt()).containsOnlyOnce(section.content()));
        assertThat(result.promptFormat()).isEqualTo(PromptFormat.STRUCTURED);
    }
}
