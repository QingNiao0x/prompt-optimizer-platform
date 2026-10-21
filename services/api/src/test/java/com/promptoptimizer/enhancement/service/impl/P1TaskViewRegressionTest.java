package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通过实际共享组装入口验证用户症状；亦可在冻结修复前源码上运行，避免测试仅镜像新实现。 */
class P1TaskViewRegressionTest {
    private final OptimizationResultAssembler assembler = new OptimizationResultAssembler();

    @Test
    void naturallyIntegratedAnswerShouldAppearOnceWithoutQuestionnaireLabels() {
        var result = result("撰写一份分析报告。", "人口数据取自统计年鉴。", "撰写分析报告。", "交付报告正文。",
                List.of(new PlanAnswer("population", "人口数据来源是什么？", "人口数据来自统计年鉴。")), List.of(), "");
        assertThat(result.optimizedPrompt().split("统计年鉴", -1)).hasSize(2);
        assertThat(result.optimizedPrompt()).doesNotContain("用户已确认的信息", "本次执行选择");
    }

    @Test
    void answeredOldChoiceBecomesAnExecutionRequirementAndNotACurrentTodo() {
        String pending = "研究地区需先确认选哪一个。";
        var result = result("撰写分析报告。\n" + pending, "分析研究。", "撰写分析报告。\n" + pending,
                "交付报告正文。", List.of(new PlanAnswer("region", "研究地区采用哪个？", "中国")), List.of(pending), "");
        assertThat(result.optimizedPrompt()).contains("中国").doesNotContain("需先确认选哪一个", "用户已确认的信息");
        assertThat(result.ambiguities()).isEmpty();
    }

    @Test
    void anotherInstitutionAndYearKeepTheirUnresolvedChoice() {
        String answered = "甲机构2024年审批阈值需先确认选哪一个。";
        String pending = "乙机构2025年审批阈值需先确认选哪一个。";
        var result = result("整理规则。\n" + answered + "\n" + pending, "两机构规则。", answered + "\n" + pending,
                "交付规则说明。", List.of(new PlanAnswer("threshold", "甲机构2024年审批阈值采用哪个？", "超过50000元")),
                List.of(answered, pending), "");
        assertThat(result.ambiguities()).contains(pending).doesNotContain(answered);
        assertThat(result.optimizedPrompt()).contains("甲机构2024年审批阈值", "超过50000元", pending);
    }

    @Test
    void partialConfirmationKeepsItsActualUnknownSubitem() {
        var result = result("整理研究执行要求。", "研究尚未执行。", "使用 R。", "交付执行要求。",
                List.of(new PlanAnswer("tool", "分析工具及版本是什么？", "使用 R，但版本暂不确定。")), List.of(), "");
        assertThat(result.optimizedPrompt()).contains("R", "版本");
        assertThat(result.ambiguities()).anyMatch(value -> value.contains("版本"));
        assertThat(result.optimizedPrompt()).doesNotContain("版本已确认");
    }

    @Test
    void noExampleContractIsAppendedToAnOrdinaryTask() {
        var result = result("解释排序算法。", "算法学习。", "解释排序算法。", "交付说明。", List.of(), List.of(), "");
        assertThat(result.sections()).noneMatch(section -> section.type() == PromptSectionType.EXAMPLES);
        assertThat(result.optimizedPrompt()).doesNotContain("输入与预期输出示例");
    }

    @Test
    void anExplicitlyRequestedDemonstrationIsKeptWithoutAppendingAnotherInstruction() {
        String example = "以下为虚构演示。\n输入示例：比较两种增强模式。\n预期输出示例：方法部分先说明对照设计。";
        var result = result("为研究生准备论文方法部分的写作提示词，提供输入与预期输出示例。", "研究尚未实施。",
                "准备论文方法部分的写作提示词。", "交付可直接使用的写作提示词。", List.of(), List.of(),
                example);
        assertThat(result.sections()).filteredOn(s -> s.type() == PromptSectionType.EXAMPLES).singleElement()
                .satisfies(s -> assertThat(s.content()).isEqualTo(example));
    }

    @Test
    void aCompleteOutputExampleInstructionIsNotDuplicatedInAnotherSection() {
        var result = result("解释排序算法，提供输入与预期输出示例。", "学习排序。", "解释排序算法。",
                "提供与本次任务相关的输入与预期输出示例。", List.of(), List.of(), "");
        assertThat(result.optimizedPrompt().split("输入与预期输出示例", -1)).hasSize(2);
    }

    @Test
    void aFictionLabelDoesNotBypassTheUserProhibitionOnAssumedData() {
        assertThatThrownBy(() -> result("解释材料，不得使用假设数据。", "材料解释。", "解释材料。", "交付说明。",
                List.of(), List.of(), "以下为虚构演示。\n输入示例：10例患者。\n预期输出示例：汇总10例。"))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }

    @Test
    void anExplicitUserExampleRequestRemainsPartOfTheTask() {
        var result = result("解释排序算法，提供输入与预期输出示例。", "算法学习。", "解释排序算法。", "交付说明。",
                List.of(), List.of(), "提供输入与预期输出示例，演示整数列表排序。");
        assertThat(result.sections()).anyMatch(s -> s.type() == PromptSectionType.EXAMPLES);
    }

    @Test
    void explicitEnglishProhibitionRejectsAContradictorySupplementarySection() {
        String raw = "Translate ‘The meeting starts at nine.’ into Chinese. Return only the translation, with no explanation or examples.";
        assertThatThrownBy(() -> result(raw, "Translation task.", "Translate the sentence into Chinese.", "Return only the translation.",
                List.of(), List.of(), "Provide input and expected output examples."))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }

    @Test
    void strictJsonDoesNotAcquireNewExampleFields() {
        assertThatThrownBy(() -> result("只输出 JSON，字段只有 title。", "标题提取。", "提取标题。", "只输出 title 字段。",
                List.of(), List.of(), "提供输入与预期输出示例。"))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }

    @Test
    void prohibitionOnAssumedDataIsNotMistakenForAHypotheticalRule() {
        var result = result("整理规则。不得使用假设数据。", "已给规则。", "整理规则。", "交付规则。",
                List.of(), List.of(), "");
        assertThat(result.optimizedPrompt()).contains("不得使用假设数据");
    }

    private com.promptoptimizer.enhancement.domain.OptimizationResult result(String raw, String background,
            String task, String output, List<PlanAnswer> answers, List<String> pending, String exampleText) {
        var sections = new ArrayList<>(List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", background),
                new PromptSection(PromptSectionType.TASK, "任务", task), new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得编造事实。")));
        if (!exampleText.isBlank()) sections.add(new PromptSection(PromptSectionType.EXAMPLES, "示例", exampleText));
        var response = new EnhancementProviderResponse(sections, "test", "test-model", false, pending);
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), "SUCCESS", List.of(), List.of(), List.of(), "v1");
        return assembler.assemble(response, context, new PromptTemplate(TemplateCode.GENERAL, "交付原定内容。", "符合原定要求。"),
                pending, answers, !answers.isEmpty(), List.of(), 1, raw);
    }
}
