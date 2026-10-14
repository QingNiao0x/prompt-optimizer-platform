package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放 candidate5 实际六题回答，核对事实、委托与未确认状态不被改成方法选值。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DelegatedPlanAnswerProjectionTest {
    private static final String ANSWER = "这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；"
            + "我没有额外真实数据，不确认任何既有参数。";
    private static final List<String> QUESTIONS = List.of("4 类任务分别是什么？", "Plan 问答的轮次与终止方式如何设定？",
            "公平对照两组获得的“相同确认信息”从哪里取得？", "获得相同确认信息的对照组是否仍可与系统交互？",
            "两名评审评分不一致时如何处理？", "主要分析是否包含效应量、置信区间和配对比较？");

    @Test void capturedResearchAnswersKeepFactsDelegationAndUnchosenParametersSeparate() {
        var answers = java.util.stream.IntStream.range(0, QUESTIONS.size())
                .mapToObj(index -> new PlanAnswer("q" + index, QUESTIONS.get(index), ANSWER)).toList();
        var task = EffectiveTaskViewFactory.create("为研究生撰写研究设计的方法部分；当前尚未实施。",
                TemplateCode.AUTO, answers, List.of(), false);

        assertThat(task.confirmedDecisions()).hasSize(6).allSatisfy(decision -> {
            assertThat(decision.answer()).isEqualTo(ANSWER);
            assertThat(decision.scope()).isEqualTo(ConfirmedPlanDecision.Scope.UNRESOLVED);
        });
        assertThat(task.pendingDecisions()).hasSize(6).allSatisfy(decision ->
                assertThat(decision.answer()).contains("不确认任何既有参数"));
        assertThat(task.executionRequirements()).allSatisfy(requirement ->
                assertThat(requirement.statement()).doesNotContain("分别：", "设定：", "从取得：", "如何处理："));

        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var type : List.of(PromptSectionType.BACKGROUND, PromptSectionType.TASK,
                PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS)) {
            sections.put(type, new PromptSection(type, type.name(), "保留原定交付。"));
        }
        PlanDecisionIntegrator.integrate(sections, task);
        String background = sections.get(PromptSectionType.BACKGROUND).content();
        String body = sections.values().stream().map(PromptSection::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(background).contains("这是尚未实施的研究。", "我没有额外真实数据");
        assertThat(body.split("这是尚未实施的研究", -1)).hasSize(2);
        assertThat(body.split("请根据原始研究目标提出可开展的推荐设计并说明适用条件", -1)).hasSize(2);
        assertThat(body).doesNotContain("设定：这是", "分别：这是", "处理：这是");
    }

    @Test void independentlyScopedFactsRemainSeparateWhenNeitherMethodIsConfirmed() {
        var task = EffectiveTaskViewFactory.create("整理两项研究的设计要求。", TemplateCode.AUTO,
                List.of(new PlanAnswer("alpha", "甲研究采用哪种方法？", "甲研究尚未实施。不确认甲研究的具体方法。"),
                        new PlanAnswer("beta", "乙研究采用哪种方法？", "乙研究已经实施。不确认乙研究的具体方法。")), List.of(), false);
        assertThat(task.confirmedDecisions()).allSatisfy(decision ->
                assertThat(decision.scope()).isEqualTo(ConfirmedPlanDecision.Scope.UNRESOLVED));
        assertThat(task.executionRequirements()).filteredOn(requirement -> requirement.destination() == PromptSectionType.BACKGROUND)
                .extracting(value -> value.statement())
                .containsExactly("甲研究尚未实施。", "乙研究已经实施。");
        assertThat(task.pendingDecisions()).hasSize(2);
    }

    @Test void bareChoicesAndPartialCurrentStateRemainUsableWithoutConfirmingTheOtherParameter() {
        var task = EffectiveTaskViewFactory.create("整理执行要求。", TemplateCode.AUTO,
                List.of(new PlanAnswer("rounds", "Plan最多允许几轮提问？", "3轮"),
                        new PlanAnswer("stack", "当前技术栈和版本是什么？", "使用 R。具体版本尚未确定。"),
                        new PlanAnswer("metrics", "甲指标和乙指标的分母分别是什么？",
                                "甲指标分母采用全部已完成事件。乙指标分母尚未确认。")), List.of(), false);
        assertThat(task.executionRequirements()).anySatisfy(requirement ->
                assertThat(requirement.statement()).contains("最大提问轮次：3轮"));
        assertThat(task.executionRequirements()).anySatisfy(requirement -> {
            assertThat(requirement.statement()).isEqualTo("使用 R。");
            assertThat(requirement.destination()).isEqualTo(PromptSectionType.BACKGROUND);
        });
        assertThat(task.executionRequirements()).anySatisfy(requirement ->
                assertThat(requirement.statement()).isEqualTo("甲指标分母采用全部已完成事件。"));
        assertThat(task.pendingDecisions()).extracting(ConfirmedPlanDecision::answer)
                .anyMatch(answer -> answer.contains("版本尚未确定"))
                .anyMatch(answer -> answer.contains("乙指标分母尚未确认"));
    }

    @Test void futureConfirmationAndRefusedParametersCannotResolveBoundOldChoices() {
        String question = "Plan轮次采用哪一个？";
        String raw = "撰写方法正文。\nPlan轮次需先确认选哪一个。";
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO,
                List.of(new PlanAnswer("rounds", question, ANSWER), new PlanAnswer("future", "乙指标分母是什么？",
                        "如果以后确认乙指标分母采用全部事件，再计算。当前暂不确定。")), List.of(), false);
        assertThat(task.effectivePrompt()).isEqualTo(raw);
        assertThat(task.executionRequirements()).noneMatch(requirement -> requirement.questionId().equals("future"));
        assertThat(task.pendingDecisions()).anySatisfy(decision ->
                assertThat(decision.answer()).contains("如果以后确认乙指标"));
    }

    @Test void actualProviderResponseCanBeAssembledWithoutSixInventedParameterAssignments() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode fixture;
        try (var stream = getClass().getResourceAsStream("/enhancement/delegated-plan-answer-candidate5.json")) {
            fixture = json.readTree(stream);
        }
        var provider = fixture.path("providerResponse");
        var response = new com.promptoptimizer.provider.domain.EnhancementProviderResponse(
                List.of(json.treeToValue(provider.path("sections"), PromptSection[].class)), "test", "test", false,
                List.of(json.treeToValue(provider.path("ambiguities"), String[].class)), List.of(),
                json.treeToValue(provider.path("assessment"), com.promptoptimizer.provider.domain.TaskEnhancementAssessment.class));
        var result = new OptimizationResultAssembler().assemble(response,
                new com.promptoptimizer.context.domain.ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new com.promptoptimizer.template.domain.PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "按原文交付。", "保留原意。", "遵守示例选择。"),
                List.of(), List.of(json.treeToValue(fixture.path("answers"), PlanAnswer[].class)), true,
                List.of(), false, 1, fixture.path("rawPrompt").asText());
        assertThat(result.optimizedPrompt()).contains("尚未实施", "不确认任何既有参数", "提出可开展的推荐设计")
                .doesNotContain("分别：这是", "设定：这是", "从取得：这是", "处理：这是", "配对比较：这是");
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.TASK).singleElement()
                .satisfies(section -> assertThat(section.content()).doesNotContain("这是尚未实施的研究"));
        // 原模型已经用自然状态句表达研究未实施，不再要求追加用户的同义原句。
        assertThat(result.optimizedPrompt()).contains("当前仅为拟定研究，尚未实施。")
                .doesNotContain("- 这是尚未实施的研究");
        assertThat(result.optimizedPrompt().split("请根据原始研究目标提出可开展的推荐设计并说明适用条件", -1)).hasSize(2);
    }

    @Test void aConditionalRefusalKeepsItsConditionAndDoesNotBecomeAnIndependentBoundary() {
        String answer = "如果以后确认乙指标分母，不确认时不得计算。当前暂不确定。";
        var task = EffectiveTaskViewFactory.create("整理甲乙指标规则。", TemplateCode.AUTO,
                List.of(new PlanAnswer("metric", "乙指标的分母是什么？", answer)), List.of(), false);
        assertThat(task.executionRequirements()).isEmpty();
        assertThat(task.pendingDecisions()).allSatisfy(decision ->
                assertThat(decision.answer()).contains("如果以后确认乙指标分母，不确认时不得计算"));
    }

    @Test void aSelfContainedActualChoiceIsNotErasedByRefusalToConfirmOtherParameters() {
        var task = EffectiveTaskViewFactory.create("整理分析要求。\n分析工具需先确认选哪一个。", TemplateCode.AUTO,
                List.of(new PlanAnswer("tool", "分析工具采用哪个？", "这是我确定采用R的决定。不确认其他参数。")), List.of(), false);
        assertThat(task.effectivePrompt()).contains("这是我确定采用R的决定").doesNotContain("分析工具需先确认选哪一个");
        assertThat(task.executionRequirements()).extracting(requirement -> requirement.statement())
                .containsExactly("这是我确定采用R的决定。");
        assertThat(task.pendingDecisions()).anySatisfy(decision ->
                assertThat(decision.answer()).contains("不确认其他参数"));
    }

    @Test void aSelfContainedDeliveryChoiceIsNotClassifiedAsAResearchState() {
        var task = EffectiveTaskViewFactory.create("整理研究内容。", TemplateCode.AUTO,
                List.of(new PlanAnswer("delivery", "本次交付内容是什么？", "这是要交付的数据表。不确认其他参数。")), List.of(), false);
        assertThat(task.executionRequirements()).singleElement().satisfies(requirement -> {
            assertThat(requirement.statement()).isEqualTo("这是要交付的数据表。");
            assertThat(requirement.destination()).isEqualTo(PromptSectionType.OUTPUT);
        });
        assertThat(task.pendingDecisions()).isNotEmpty();
    }

    @Test void aMaterialStatementCannotHideAConnectedActualMethodChoice() {
        for (String selected : List.of("我已有数据，分析工具采用R。", "我已有数据并采用R。")) {
            var task = EffectiveTaskViewFactory.create("整理分析要求。\n分析工具需先确认选哪一个。", TemplateCode.AUTO,
                    List.of(new PlanAnswer("tool", "分析工具采用哪个？", selected + "不确认其他参数。")), List.of(), false);
            assertThat(task.effectivePrompt()).contains(selected).doesNotContain("分析工具需先确认选哪一个");
            assertThat(task.executionRequirements()).extracting(requirement -> requirement.statement()).containsExactly(selected);
        }
    }

    @Test void aConditionalNonconfirmationRuleDoesNotAssertACurrentlyUnchosenParameter() {
        for (String rule : List.of("不确认时不得计算。", "不确认口径时不得计算。",
                "不确认则不进行分析。", "不确认就不进行分析。")) {
            var task = EffectiveTaskViewFactory.create("整理执行规则。", TemplateCode.AUTO,
                    List.of(new PlanAnswer("rule", "计算前须遵守什么规则？", rule)), List.of(), false);
            assertThat(task.pendingDecisions()).isEmpty();
            assertThat(task.executionRequirements()).singleElement().satisfies(requirement ->
                    assertThat(requirement.statement()).endsWith(rule));
        }
    }

    @Test void aRefusedNamedParameterIsNotAConditionalRule() {
        var task = EffectiveTaskViewFactory.create("整理就诊流程。", TemplateCode.AUTO,
                List.of(new PlanAnswer("time", "就诊时间是什么？", "不确认就诊时间。")), List.of(), false);
        assertThat(task.pendingDecisions()).singleElement().satisfies(decision ->
                assertThat(decision.answer()).isEqualTo("不确认就诊时间。"));
    }
}
