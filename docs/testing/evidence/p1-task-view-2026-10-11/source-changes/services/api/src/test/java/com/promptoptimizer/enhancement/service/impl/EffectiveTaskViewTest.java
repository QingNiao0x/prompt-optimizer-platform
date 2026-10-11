package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EffectiveTaskViewTest {
    @Test void viewKeepsOriginalAndReplacesOnlyTheBoundOldChoice() {
        String raw = "撰写报告。\n研究地区需先确认选哪一个。\n2025年乙机构审批阈值需先确认选哪一个。";
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO,
                List.of(new PlanAnswer("region", "研究地区采用哪个？", "中国")), List.of(), true);
        assertThat(task.originalPrompt()).isEqualTo(raw);
        assertThat(task.effectivePrompt()).contains("研究地区范围：中国", "2025年乙机构审批阈值需先确认选哪一个")
                .doesNotContain("研究地区需先确认选哪一个");
        assertThat(task.intent().engineeringConstraints()).isFalse();
        assertThat(task.modelInput().executionRequirements()).extracting(value -> value.statement())
                .containsExactlyElementsOf(task.executionRequirements().stream().map(value -> value.statement()).toList());
    }

    @Test void quotedAndFencedOldQuestionsAreNotCurrentInstructions() {
        String q = "研究地区采用哪个？";
        String raw = "> " + q + "\n```text\n" + q + "\n```\n解释“" + q + "”这个问题。";
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO,
                List.of(new PlanAnswer("region", q, "中国")), List.of(), false);
        assertThat(task.effectivePrompt()).isEqualTo(raw);
    }

    @Test void futureConfirmationDoesNotSetAnyCurrentChoice() {
        var task = EffectiveTaskViewFactory.create("整理研究要求。", TemplateCode.AUTO,
                List.of(new PlanAnswer("tool", "采用哪个分析工具？", "如果以后确认使用 R，再采用。当前暂不确定。")), List.of(), false);
        assertThat(task.executionRequirements()).isEmpty();
        assertThat(task.pendingDecisions()).isNotEmpty();
    }

    @Test void naturalEquivalentUsesOneExpressionAcrossTheFourParts() {
        var task = EffectiveTaskViewFactory.create("撰写报告。", TemplateCode.AUTO,
                List.of(new PlanAnswer("population", "人口数据来源是什么？", "人口数据来自统计年鉴。")), List.of(), false);
        var sections = sections("人口数据取自统计年鉴。");
        assertThat(PlanDecisionIntegrator.covered(sections, task.executionRequirements().getFirst())).isTrue();
        PlanDecisionIntegrator.integrate(sections, task);
        assertThat(sections.values().stream().map(PromptSection::content).collect(java.util.stream.Collectors.joining()))
                .doesNotContain("来自统计年鉴");
    }

    @Test void aNegatedOrHypotheticalValueDoesNotCountAsImplemented() {
        var task = EffectiveTaskViewFactory.create("整理分析要求。", TemplateCode.AUTO,
                List.of(new PlanAnswer("tool", "采用哪个分析工具？", "使用 R。")), List.of(), false);
        assertThat(PlanDecisionIntegrator.covered(sections("不使用 R。"), task.executionRequirements().getFirst())).isFalse();
        assertThat(PlanDecisionIntegrator.covered(sections("未使用 R。"), task.executionRequirements().getFirst())).isFalse();
        assertThat(PlanDecisionIntegrator.covered(sections("如果采用 R。"), task.executionRequirements().getFirst())).isFalse();
        assertThat(PlanDecisionIntegrator.covered(sections("若采用 R，则执行分析。"), task.executionRequirements().getFirst())).isFalse();
    }

    @Test void numericOperatorAndIdentifierBoundariesRemainDistinct() {
        var task = EffectiveTaskViewFactory.create("整理规则。", TemplateCode.AUTO,
                List.of(new PlanAnswer("threshold", "甲机构阈值是什么？", "甲机构金额>500.5元。")), List.of(), false);
        var rule = task.executionRequirements().getFirst();
        assertThat(PlanDecisionIntegrator.covered(sections("甲机构金额>=500.5元。"), rule)).isFalse();
        assertThat(PlanDecisionIntegrator.covered(sections("甲机构金额>5005元。"), rule)).isFalse();
        var model = EffectiveTaskViewFactory.create("整理模型配置。", TemplateCode.AUTO,
                List.of(new PlanAnswer("model", "当前技术栈是什么？", "Model AB")), List.of(), false);
        assertThat(PlanDecisionIntegrator.covered(sections("技术栈：ModelAB。"), model.executionRequirements().getFirst())).isFalse();
    }

    @Test void sameValueOnAnotherInstitutionDoesNotCoverABoundBareAnswer() {
        var task = EffectiveTaskViewFactory.create("整理规则。", TemplateCode.AUTO,
                List.of(new PlanAnswer("threshold", "甲机构2024年审批阈值是什么？", "50000元")), List.of(), false);
        assertThat(PlanDecisionIntegrator.covered(sections("乙机构2025年审批阈值：50000元。"), task.executionRequirements().getFirst())).isFalse();
    }

    @Test void capturedResearchClausesAndLayoutSpacesDoNotReintroduceTheWholeAnswer() {
        var task = EffectiveTaskViewFactory.create("撰写2015—2025年死亡率分析报告。", TemplateCode.AUTO,
                List.of(new PlanAnswer("subtype", "分亚类具体指哪些亚类？",
                                "疾病口径采用 ICD-10 I00-I69；亚类为缺血性心脏病、脑卒中、高血压性心脏病。"),
                        new PlanAnswer("yll", "YLL计算采用哪种标准期望寿命？", "YLL 按 WHO/GBD 标准期望寿命表计算。"),
                        new PlanAnswer("arriaga", "Arriaga分解用于分解哪两个对象之间的差异？",
                                "Arriaga 用于分解预期寿命差异，比较2015年与2025年、城乡、男女；分解死亡率差异时比较亚类贡献。")),
                List.of(), false);
        var sections = sections("疾病口径采用ICD-10 I00-I69，亚类包括缺血性心脏病、脑卒中、高血压性心脏病。\n"
                + "YLL按WHO/GBD标准期望寿命表计算。\n"
                + "4. Arriaga分解：分解预期寿命差异，比较2015年与2025年、城乡、男女；分解死亡率差异时比较亚类贡献。");
        String original = sections.get(PromptSectionType.TASK).content();
        PlanDecisionIntegrator.integrate(sections, task);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(original);
    }

    @Test void oneMissingChildRestoresOnlyThatChild() {
        var task = EffectiveTaskViewFactory.create("撰写报告。", TemplateCode.AUTO,
                List.of(new PlanAnswer("subtype", "疾病口径与亚类是什么？",
                        "疾病口径采用 ICD-10 I00-I69；亚类为缺血性心脏病、脑卒中。")), List.of(), false);
        var sections = sections("疾病口径采用ICD-10 I00-I69。");
        PlanDecisionIntegrator.integrate(sections, task);
        assertThat(sections.get(PromptSectionType.TASK).content())
                .isEqualTo("疾病口径采用ICD-10 I00-I69。\n- 亚类为缺血性心脏病、脑卒中。");
    }

    @Test void capturedSortingInstructionsCoverTheActuallyConfirmedChoices() {
        var task = EffectiveTaskViewFactory.create("弄个排序", TemplateCode.AUTO,
                List.of(new PlanAnswer("target", "你要对什么内容进行排序？", "整数列表，要求处理空列表和重复元素。"),
                        new PlanAnswer("direction", "排序方向是升序还是降序？", "升序。"),
                        new PlanAnswer("output", "排序结果以什么形式给你？",
                                "使用 Python 编写快速排序函数，提供代码及必要注释，并展示输入与预期输出示例。")), List.of(), true);
        var sections = sections("使用 Python 编写一个快速排序函数，对整数列表按整数值进行升序排序。\n"
                + "输入为整数列表，需正确处理空列表和重复元素。\n函数需提供必要注释。\n展示输入与预期输出示例。");
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出",
                "交付一份 Python 快速排序函数代码，包含必要注释，并附输入与预期输出示例。"));
        String original = sections.get(PromptSectionType.TASK).content();
        PlanDecisionIntegrator.integrate(sections, task);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(original);
        assertThat(task.executionRequirements()).extracting(value -> value.statement()).contains("按升序排序。");
    }

    @Test void quotedCodeAndNumericAssignmentPrefixesAreNotCoverage() {
        var task = EffectiveTaskViewFactory.create("整理分析要求。", TemplateCode.AUTO,
                List.of(new PlanAnswer("tool", "采用哪个分析工具？", "使用 R。")), List.of(), false);
        assertThat(PlanDecisionIntegrator.covered(sections("```text\n使用 R。\n```"), task.executionRequirements().getFirst())).isFalse();
        var threshold = EffectiveTaskViewFactory.create("整理规则。", TemplateCode.AUTO,
                List.of(new PlanAnswer("threshold", "甲机构2024年阈值是什么？", "500元")), List.of(), false);
        assertThat(PlanDecisionIntegrator.covered(sections("甲机构2024年阈值为5000元。"), threshold.executionRequirements().getFirst())).isFalse();
    }

    @Test void capturedProWordOrderPreservesTheSameBoundObjectAndFullValues() {
        var task = EffectiveTaskViewFactory.create("撰写2015—2025年死亡率分析报告。", TemplateCode.AUTO,
                List.of(new PlanAnswer("population", "分人群具体按什么维度划分？",
                                "人群按年龄组（0-14、15-44、45-64、65+）比较。"),
                        new PlanAnswer("subtype", "分亚类具体指哪些亚类？",
                                "疾病口径采用 ICD-10 I00-I69；亚类为缺血性心脏病、脑卒中、高血压性心脏病。"),
                        new PlanAnswer("arriaga", "Arriaga分解用于分解哪两个对象之间的差异？",
                                "Arriaga 用于分解预期寿命差异，比较2015年与2025年、城乡、男女；分解死亡率差异时比较亚类贡献。")), List.of(), false);
        var sections = sections("分性别、地区、人群、亚类比较，人群按年龄组（0-14、15-44、45-64、65+）划分。\n"
                + "4. 采用Arriaga分解方法分析预期寿命差异，比较2015年与2025年、城乡、男女；分解死亡率差异时比较亚类贡献。");
        sections.put(PromptSectionType.BACKGROUND, new PromptSection(PromptSectionType.BACKGROUND, "背景",
                "采用ICD-10 I00-I69疾病口径，亚类为缺血性心脏病、脑卒中、高血压性心脏病。"));
        String original = sections.get(PromptSectionType.TASK).content();
        PlanDecisionIntegrator.integrate(sections, task);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(original);
        var incomplete = sections("人群按年龄组（0-14、15-44、45-64、65+）划分。");
        assertThat(PlanDecisionIntegrator.covered(incomplete, task.executionRequirements().getFirst())).isFalse();
    }

    private EnumMap<PromptSectionType, PromptSection> sections(String text) {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var type : List.of(PromptSectionType.BACKGROUND, PromptSectionType.TASK, PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS))
            sections.put(type, new PromptSection(type, type.name(), type == PromptSectionType.TASK ? text : "保持原定目标。"));
        return sections;
    }
}
