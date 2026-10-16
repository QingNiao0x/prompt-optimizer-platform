package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放十九的量尺漏投影及普通反问；不修改真实作品、不选择任何模型推荐参数。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateTwentyReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(ints = {281, 283})
    void currentDeclaredScoringMeasureReachesExecutionWithoutCopyingProposedValues(int trace) throws Exception {
        var fixture = fixture("trace" + trace);
        var sections = sections(fixture);
        var task = task(fixture);
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment(fixture));
        String value = sections.get(PromptSectionType.TASK).content();
        assertThat(value).contains("提出拟议评分尺度", "各等级评分锚点", "分歧处理规则")
                .doesNotContain("5点Likert", "1=完全不符合", "7点李克特");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment(fixture));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(value);
    }

    @Test
    void capturedQuestionVariantsDoNotLoseActualTerminationOrIndependentWorkflowChoices() throws Exception {
        var fixture = fixture("plan280");
        var questions = JSON.readerForListOf(PlanQuestion.class).<List<PlanQuestion>>readValue(fixture.path("questions"));
        var result = new PlanQuestionFilter().filter(questions,
                new PlanningProviderRequest(fixture.path("rawPrompt").asText(), "", List.of()));
        assertThat(result).extracting(PlanQuestion::id).containsExactly("q1", "q2", "q4");
        assertThat(questions).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"评分量尺由我决定。", "评分量尺采用1至7分。", "评分量尺为1至7分。", "评分量尺以后确认后再设计。", "不要提出评分量尺。"})
    void aNewAliasCannotOverrideUserReservedChosenOrFutureScales(String boundary) throws Exception {
        var fixture = fixture("trace283");
        var sections = sections(fixture);
        var task = EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText() + boundary,
                TemplateCode.AUTO, List.of(), List.of(), false);
        String before = sections.get(PromptSectionType.TASK).content();
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment(fixture));
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(before);
    }

    @Test
    void delegatedFieldsBecomePositiveRequirementsAndKeepAllSourceRecords() throws Exception {
        var fixture = fixture("trace281");
        var task = task(fixture);
        var sections = sections(fixture);
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不确认任何既有参数。"));
        var reminders = task.pendingDecisions().stream().map(p -> "已委托提出拟议设计，实际采用参数仍未确认：" + p.question()).toList();
        var before = task.modelInput();
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, reminders, task);
        assertThat(sections.get(PromptSectionType.TASK).content()).contains("Plan组的问答交互的终止条件", "四个维度的评分量尺");
        var remaining = PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, reminders, task);
        assertThat(remaining).hasSize(1).noneMatch(s -> s.contains("在什么条件下结束") || s.contains("评分采用什么量尺"));
        assertThat(task.pendingDecisions()).hasSize(5);
        assertThat(task.modelInput()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"另一项研究的评分量尺未定。", "以后确认后再设计评分量尺。", "示例：评分量尺未定。"})
    void indirectCompletionCannotBorrowAnotherScopeOrFuturePermission(String missing) throws Exception {
        var fixture = fixture("trace283");
        var sections = sections(fixture);
        String before = sections.get(PromptSectionType.TASK).content();
        var original = assessment(fixture);
        var gap = original.gaps().getFirst();
        var assessment = new TaskEnhancementAssessment(original.sufficiency(), List.of(new TaskEnhancementAssessment.Gap(
                gap.kind(), missing, gap.completion(), gap.basis())), original.presentation());
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task(fixture), assessment);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(before);
    }

    @Test
    void metadataWithUserReservedScoringOrJournalCountingRemainsAQuestion() throws Exception {
        var fixture = fixture("plan280");
        var questions = JSON.readerForListOf(PlanQuestion.class).<List<PlanQuestion>>readValue(fixture.path("questions"));
        var score = questions.get(3);
        var count = questions.get(4);
        var reserved = new PlanQuestion(score.id(), score.question(), score.hint() + "评分由用户决定。",
                score.type(), score.options(), score.examples(), true);
        var journal = new PlanQuestion(count.id(), count.question(), count.hint() + "需遵守期刊投稿规范。",
                count.type(), count.options(), count.examples(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(reserved, journal),
                new PlanningProviderRequest(fixture.path("rawPrompt").asText(), "", List.of())))
                .containsExactly(reserved, journal);
    }

    @Test
    void futurePermissionForOneFieldCannotBorrowCurrentDelegationOfAnotherField() throws Exception {
        var fixture = fixture("trace281");
        var original = JSON.readerForListOf(PlanAnswer.class).<List<PlanAnswer>>readValue(fixture.path("actualAnswers"));
        var answers = original.stream().map(a -> a.questionId().equals("q1")
                ? new PlanAnswer(a.questionId(), a.question(), "如果以后批准，再提出设计。") : a).toList();
        var task = EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO, answers, List.of(), false);
        var sections = sections(fixture);
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不确认任何既有参数。"));
        // 未来许可可能登记在条件视图；显式传入原题提醒，独立检查本投影器不能借另一题的当前委托删去它。
        var reminders = original.stream().map(p -> "已委托提出拟议设计，实际采用参数仍未确认：" + p.question()).toList();
        PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, reminders, task);
        assertThat(sections.get(PromptSectionType.TASK).content()).doesNotContain("Plan组的问答交互的终止条件");
        assertThat(PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, reminders, task))
                .anyMatch(s -> s.contains("在什么条件下结束"));
    }

    /** 从受控资源读取完整原件，测试不改写评分声明或真实问题。 */
    private JsonNode fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/enhancement/candidate20-" + name + ".json")) {
            return JSON.readTree(input);
        }
    }

    /** 只绑定夹具中实际提交的答案，不为直接组补用户未确认的选项。 */
    private com.promptoptimizer.enhancement.domain.EffectiveTaskView task(JsonNode fixture) throws Exception {
        List<PlanAnswer> answers = fixture.has("actualAnswers")
                ? JSON.readerForListOf(PlanAnswer.class).readValue(fixture.path("actualAnswers")) : List.of();
        return EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO, answers, List.of(), false);
    }

    /** 派生内部呈现视图沿用生产研究归一化，原始gap及候选取值均不变。 */
    private TaskEnhancementAssessment assessment(JsonNode fixture) throws Exception {
        var value = fixture.path("response").path("assessment").deepCopy();
        // 283省略内部呈现字段；沿用现有研究Provider的STRUCTURED归一化，仅构造派生视图，不改原件或评分声明。
        if (!value.has("presentation")) ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("presentation", "STRUCTURED");
        return JSON.treeToValue(value, TaskEnhancementAssessment.class);
    }

    /** 在原责任段的副本上检查投影，不能让元数据代替复制正文。 */
    private EnumMap<PromptSectionType, PromptSection> sections(JsonNode fixture) throws Exception {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var node : fixture.path("response").path("sections")) {
            var section = JSON.treeToValue(node, PromptSection.class);
            result.put(section.type(), section);
        }
        return result;
    }
}
