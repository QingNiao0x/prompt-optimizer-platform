package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证结构化组装能省去已完整覆盖的辅助段，旧自然组装与独立条件仍保持原行为。 */
class StructuredAssemblyQualityTest {
    private static final String RAW = "整理记录并说明处理流程。";
    private static final String RULE = "说明每个步骤使用的输入和预期输出。";
    private static final ContextSnapshot CONTEXT = new ContextSnapshot("", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "fixture");

    @Test
    void structuredClearsCompletelyCoveredOutputWithoutChangingLegacyCompactAssembly() {
        var structured = assemble(RULE, PromptFormat.STRUCTURED);
        var compact = assemble(RULE, PromptFormat.COMPACT);
        assertThat(structured.optimizedPrompt()).containsOnlyOnce(RULE);
        assertThat(structured.sections()).noneMatch(section -> section.type() == PromptSectionType.OUTPUT);
        assertThat(compact.sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo(PromptSectionType.OUTPUT);
            assertThat(section.content()).isEqualTo(RULE);
        });
    }

    @Test
    void structuredKeepsIndependentObjectsConditionsNegationExceptionsAndSourceText() {
        String independent = "甲机构2025年采用月度汇总；乙机构2024年采用季度汇总。\n"
                + "如果以后确认新口径，再调整对应步骤；当前不适用。\n"
                + "例外：归档记录仅核查，不作修改。\n> 原始资料：" + RULE + "\n"
                + "```text\n" + RULE + "\n```";
        var result = assemble(RULE + "\n" + independent, PromptFormat.STRUCTURED);
        assertThat(result.sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo(PromptSectionType.OUTPUT);
            assertThat(section.content()).contains(independent).doesNotStartWith(RULE);
        });
    }

    @Test
    void authorityCanClearAnEntireDuplicatedAuxiliarySlotOnlyForStructured() {
        var sections = new java.util.EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", RAW));
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", RULE));
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", RULE));
        var legacy = new java.util.EnumMap<>(sections);
        AuthoritativeDeliveryCompactor.compact(sections, java.util.Map.of(PromptSectionType.CONSTRAINTS, RULE), true);
        AuthoritativeDeliveryCompactor.compact(legacy, java.util.Map.of(PromptSectionType.CONSTRAINTS, RULE));
        assertThat(sections.get(PromptSectionType.OUTPUT).content()).isEmpty();
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).isEqualTo(RULE);
        assertThat(legacy.get(PromptSectionType.OUTPUT).content()).isEqualTo(RULE);
    }

    @Test
    void actual470ProjectionRetainsSavedRequirementsWithoutAddingGenericAcceptance() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var path = java.nio.file.Path.of("../../docs/testing/evidence/me02-candidate43-concise-output-2026-10-19/cases/AI-USAGE/STRUCTURED/assembled-result.json");
        var saved = json.readTree(path.toFile());
        var original = new OptimizationResult(saved.path("optimizedPrompt").asText(),
                json.readerForListOf(PromptSection.class).readValue(saved.path("sections")), CONTEXT,
                List.of(), List.of(), TemplateCode.GENERAL, new ProviderMetadata("offline", "actual-470", true), 1);
        var projected = PromptDocument.structured(original);
        assertThat(projected.sections()).extracting(PromptSection::type).doesNotContain(PromptSectionType.ACCEPTANCE);
        original.sections().forEach(section -> assertThat(projected.optimizedPrompt()).contains(section.content()));
    }

    private OptimizationResult assemble(String output, PromptFormat format) {
        var sections = List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", ""),
                new PromptSection(PromptSectionType.TASK, "任务", RAW + RULE),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得虚构数据或来源。"));
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.SUFFICIENT,
                List.of(), TaskEnhancementAssessment.Presentation.STRUCTURED);
        var task = EffectiveTaskViewFactory.create(RAW, TemplateCode.GENERAL, List.of(), List.of());
        return new OptimizationResultAssembler().assemble(new EnhancementProviderResponse(sections, "offline", "fixture",
                        true, List.of(), List.of(), assessment), CONTEXT,
                new PromptTemplate(TemplateCode.GENERAL, "保持原定交付。", "核查实际交付。"),
                List.of(), List.of(), false, ConstraintBundle.fromLegacy(List.of(), false), 1, RAW,
                List.of(), List.of(), task, format);
    }

    @Test
    void completedStateIsExplicitOnlyForFullyBoundStructuredAnswers() {
        for (String answer : List.of("顺延一周，仍线下举行。", "暂不确定，不能代替我选择。",
                "使用R，版本尚未确定。", "如果以后确认顺延一周，再更新；目前尚未确认。")) {
            String raw = "起草研讨会变更通知，方案需先由用户确认，不承诺退款。";
            var answers = List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer("change", "会议变更方案是什么？", answer));
            var task = EffectiveTaskViewFactory.create(raw, TemplateCode.GENERAL, answers, List.of());
            var response = new EnhancementProviderResponse(List.of(
                    new PromptSection(PromptSectionType.BACKGROUND, "背景", ""),
                    new PromptSection(PromptSectionType.TASK, "任务", "起草变更通知。"),
                    new PromptSection(PromptSectionType.OUTPUT, "输出", "交付通知正文。"),
                    new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得承诺退款。")), "offline", "fixture", true,
                    List.of(), List.of(), new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.SUFFICIENT,
                    List.of(), TaskEnhancementAssessment.Presentation.STRUCTURED));
            var template = new PromptTemplate(TemplateCode.GENERAL, "交付通知正文。", "");
            for (var format : PromptFormat.values()) {
                var result = new OptimizationResultAssembler().assemble(response, CONTEXT, template, List.of(), answers, true,
                        ConstraintBundle.fromLegacy(List.of(), false), 1, raw, List.of(), List.of(), task, format);
                if (format == PromptFormat.STRUCTURED && answer.equals("顺延一周，仍线下举行。"))
                    assertThat(result.optimizedPrompt()).contains("本次已完成确认：").contains(answer);
                else assertThat(result.optimizedPrompt()).doesNotContain("本次已完成确认：");
                assertThat(result.optimizedPrompt()).contains("不承诺退款");
            }
        }
    }
}
