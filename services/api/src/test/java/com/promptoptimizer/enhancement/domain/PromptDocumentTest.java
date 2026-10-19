package com.promptoptimizer.enhancement.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 五模块投影不得丢失旧链路的独立未决条件或示例交付要求。 */
class PromptDocumentTest {
    @Test
    void emptyAuxiliaryModulesDoNotGenerateBoilerplateOrEmptyHeadings() {
        var original = result(List.of(new PromptSection(PromptSectionType.TASK, "任务", "把下列句子译成英文，只输出译文。")), List.of());
        var projected = PromptDocument.structured(original);
        assertThat(projected.sections()).extracting(PromptSection::type).containsExactly(PromptSectionType.TASK);
        assertThat(projected.optimizedPrompt()).isEqualTo("## 任务\n把下列句子译成英文，只输出译文。");
        assertThat(PromptDocument.legacy(original).optimizedPrompt()).isEqualTo(original.optimizedPrompt());
    }

    @Test
    void authoritativeClarificationListDoesNotBecomeASecondBackgroundQuestionnaire() {
        String question = "实际采用哪一份资料仍需用户确认。";
        var projected = PromptDocument.structured(result(List.of(
                new PromptSection(PromptSectionType.TASK, "任务", "比较两份资料。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", question),
                new PromptSection(PromptSectionType.CLARIFICATIONS, "待确认", "- " + question)), List.of(question)));
        assertThat(projected.optimizedPrompt()).containsOnlyOnce(question).doesNotContain("## 背景");
        assertThat(projected.ambiguities()).containsExactly(question);
    }

    private OptimizationResult result(List<PromptSection> sections, List<String> ambiguities) {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        return new OptimizationResult("既有正文", sections, context, ambiguities, List.of(), TemplateCode.GENERAL,
                new ProviderMetadata("mock", "fixture", true), 1);
    }

    @Test
    void aListedQuestionWithoutFullExecutionCoverageStillEntersTheCopiedConstraints() {
        String question = "乙机构2026年实际采用的标准人口仍未确定。";
        var projected = PromptDocument.structured(result(List.of(
                new PromptSection(PromptSectionType.TASK, "任务", "比较两机构的死亡率。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "甲机构2025年采用资料中已确认的标准人口。"),
                new PromptSection(PromptSectionType.CLARIFICATIONS, "待确认", "- " + question)), List.of(question)));
        assertThat(projected.optimizedPrompt()).containsOnlyOnce(question)
                .contains("甲机构2025年采用资料中已确认的标准人口。");
        assertThat(projected.sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo(PromptSectionType.CONSTRAINTS);
            assertThat(section.content()).contains(question);
        });
    }

    @Test
    void quotedOrConditionallyScopedTextDoesNotReplaceAnIndependentPendingRequirement() {
        String question = "实际采用的评分参数尚未确认。";
        for (String scoped : List.of("```text\n" + question + "\n```", "### 历史条件\n" + question,
                "> " + question, "    " + question)) {
            var projected = PromptDocument.structured(result(List.of(
                    new PromptSection(PromptSectionType.TASK, "任务", "提出拟议设计。\n" + scoped),
                    new PromptSection(PromptSectionType.CLARIFICATIONS, "待确认", "- " + question)), List.of(question)));
            assertThat(projected.sections()).anySatisfy(section -> {
                assertThat(section.type()).isEqualTo(PromptSectionType.CONSTRAINTS);
                assertThat(section.content()).contains(question);
            });
        }
    }

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
