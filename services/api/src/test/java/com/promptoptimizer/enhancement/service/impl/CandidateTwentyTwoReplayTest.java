package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 回放二十一真实题答和响应，只验证平台投影；不修改原件或推荐参数。 */
class CandidateTwentyTwoReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ContextSnapshot EMPTY =
            new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
    private static final String STATUS = "已委托提出拟议设计，实际采用参数仍未确认";

    @Test
    void capturedPlanAssemblyProjectsScoringAndStatesOnceWithoutDroppingQuestions() throws Exception {
        var fixture = fixture("trace298");
        var answers = answers(fixture);
        String raw = fixture.path("rawPrompt").asText();
        String original = fixture.toString();
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of());
        var originalInput = task.modelInput();
        var result = new OptimizationResultAssembler().assemble(response(fixture), EMPTY,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, true,
                new ConstraintCompleterImpl().completeForTask(EMPTY, PermissionPolicyInput.empty(), true,
                        TaskIntentResolver.resolve(TemplateCode.AUTO, raw)), 0, raw, List.of(), List.of(), task);
        assertThat(result.optimizedPrompt()).contains("各等级评分锚点", "分歧处理规则");
        assertThat(count(result.optimizedPrompt(), STATUS)).isEqualTo(1);
        for (var answer : answers) assertThat(result.optimizedPrompt()).contains(answer.question());
        assertThat(task.pendingDecisions()).hasSize(7);
        assertThat(task.confirmedDecisions()).hasSize(7);
        assertThat(task.modelInput()).isEqualTo(originalInput);
        assertThat(fixture.toString()).isEqualTo(original);
    }

    @Test
    void actualSevenQuestionsOnlyLoseDelegatedScoringAndOrdinaryBodyCount() throws Exception {
        var fixture = fixture("plan296");
        List<PlanQuestion> questions = JSON.readerForListOf(PlanQuestion.class).readValue(fixture.path("questions"));
        var result = new PlanQuestionFilter().filter(questions, input(fixture.path("rawPrompt").asText()));
        assertThat(result).extracting(PlanQuestion::id).containsExactly("q1", "q2", "q3", "q4", "q5");
    }

    @Test
    void currentQualityDeclarationAloneReachesBodyAndProjectionIsIdempotent() throws Exception {
        var fixture = fixture("trace298");
        var original = response(fixture).assessment();
        var gap = original.gaps().stream().filter(value -> value.kind() == TaskEnhancementAssessment.Kind.QUALITY_CHECK
                && value.missing().contains("四项评价")).findFirst().orElseThrow();
        var assessment = new TaskEnhancementAssessment(original.sufficiency(), List.of(gap), original.presentation());
        var task = EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO,
                answers(fixture), List.of());
        var sections = sections(fixture);
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment);
        String first = sections.get(PromptSectionType.TASK).content();
        assertThat(first).contains("各等级评分锚点").doesNotContain("1至7分", "1至5分");
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, assessment);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(first);
    }

    @Test
    void commonStateProjectionPreservesEveryQuestionAndRejectsMixedUnboundAndFutureStates() throws Exception {
        var fixture = fixture("trace298");
        var answers = answers(fixture);
        String raw = fixture.path("rawPrompt").asText();
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of());
        var messages = answers.stream().map(value -> STATUS + "：" + value.question()).toList();
        assertThat(PlanDecisionIntegrator.commonDelegatedQuestions(messages, task)).hasValue(
                answers.stream().map(PlanAnswer::question).toList());
        var mixed = new java.util.ArrayList<>(messages);
        mixed.set(0, "评分形式尚未确定，需由用户选择。");
        assertThat(PlanDecisionIntegrator.commonDelegatedQuestions(mixed, task)).isEmpty();
        mixed.set(0, STATUS + "：另一研究的评分形式如何设计？");
        assertThat(PlanDecisionIntegrator.commonDelegatedQuestions(mixed, task)).isEmpty();
        var futureAnswers = new java.util.ArrayList<>(answers);
        futureAnswers.set(0, new PlanAnswer(answers.getFirst().questionId(), answers.getFirst().question(),
                "如果以后批准，再提出设计。"));
        var future = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, futureAnswers, List.of());
        assertThat(PlanDecisionIntegrator.commonDelegatedQuestions(messages, future)).isEmpty();
    }

    @Test
    void independentConditionsInHintsCannotBeRemovedWithOrdinaryQuestionWording() throws Exception {
        var fixture = fixture("plan296");
        List<PlanQuestion> questions = JSON.readerForListOf(PlanQuestion.class).readValue(fixture.path("questions"));
        var scoring = questions.stream().filter(value -> value.id().equals("q7")).findFirst().orElseThrow();
        var counting = questions.stream().filter(value -> value.id().equals("q8")).findFirst().orElseThrow();
        var conditional = new PlanQuestion(scoring.id(), scoring.question(),
                "评分差异超过2级时是否增加第三位评审？", scoring.type(), scoring.options(), scoring.examples(), true);
        var journal = new PlanQuestion(counting.id(), counting.question(),
                "需遵守期刊投稿规范。", counting.type(), counting.options(), counting.examples(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(conditional, journal),
                input(fixture.path("rawPrompt").asText()))).containsExactly(conditional, journal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"评分形式由我决定。", "评分形式以后确认。", "不得推荐评分形式。", "评分形式为1至7分。"})
    void reservedFutureNegativeAndSelectedScoringCannotBeReplaced(String boundary) throws Exception {
        var fixture = fixture("trace298");
        var sections = sections(fixture);
        String raw = fixture.path("rawPrompt").asText() + boundary;
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers(fixture), List.of());
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, response(fixture).assessment());
        assertThat(sections.get(PromptSectionType.TASK).content()).doesNotContain("各等级评分锚点");
    }

    @ParameterizedTest
    @ValueSource(strings = {"评分形式由我决定。", "请按期刊投稿规范计算字数。"})
    void explicitResponsibilityAndSubmissionRulesKeepTheirQuestions(String boundary) throws Exception {
        var fixture = fixture("plan296");
        List<PlanQuestion> questions = JSON.readerForListOf(PlanQuestion.class).readValue(fixture.path("questions"));
        String target = boundary.startsWith("评分") ? "q7" : "q8";
        var question = questions.stream().filter(value -> value.id().equals(target)).findFirst().orElseThrow();
        assertThat(new PlanQuestionFilter().filter(List.of(question),
                input(fixture.path("rawPrompt").asText() + boundary))).containsExactly(question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"另一研究：", "示例：", "如果以后确认：", "不得提供评分形式。"})
    void foreignConditionalAndNegativeMetadataCannotCreateCurrentScoringDuty(String prefix) throws Exception {
        var fixture = fixture("trace298");
        var sections = sections(fixture);
        var declared = new TaskEnhancementAssessment.Gap(TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION,
                prefix + "评分形式缺失", prefix + "评分形式给出拟议建议及适用条件。", "当前委托");
        var assessment = response(fixture).assessment();
        var changed = new TaskEnhancementAssessment(assessment.sufficiency(), List.of(declared), assessment.presentation());
        var task = EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO,
                answers(fixture), List.of());
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, changed);
        assertThat(sections.get(PromptSectionType.TASK).content()).doesNotContain("各等级评分锚点");
    }

    /** 使用实际响应的兼容呈现归一化，不改事实、参数或原始文件。 */
    private EnhancementProviderResponse response(JsonNode fixture) throws Exception {
        var value = fixture.path("response");
        ObjectNode assessment = value.path("assessment").deepCopy();
        if (!assessment.has("presentation")) assessment.put("presentation", "STRUCTURED");
        return new EnhancementProviderResponse(JSON.readerForListOf(PromptSection.class).readValue(value.path("sections")),
                "offline-replay", "deepseek-flash", false,
                JSON.readerForListOf(String.class).readValue(value.path("ambiguities")), List.of(),
                JSON.treeToValue(assessment, TaskEnhancementAssessment.class));
    }

    /** 读取真实绑定答案，禁止补入选项或推断确认值。 */
    private List<PlanAnswer> answers(JsonNode fixture) throws Exception {
        return JSON.readerForListOf(PlanAnswer.class).readValue(fixture.path("actualAnswers"));
    }

    /** 生成副本供局部投影比较，原始响应不变。 */
    private EnumMap<PromptSectionType, PromptSection> sections(JsonNode fixture) throws Exception {
        var map = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var section : response(fixture).sections()) map.put(section.type(), section);
        return map;
    }

    /** 无额外资料的当前规划请求。 */
    private PlanningProviderRequest input(String raw) {
        return new PlanningProviderRequest(raw, "", List.of());
    }

    /** 固定字节来源可由夹具的sourceSha256追溯。 */
    private JsonNode fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/enhancement/candidate22-" + name + ".json")) {
            return JSON.readTree(stream);
        }
    }

    /** 统计共同状态，不以问题数量下降代替状态与来源完整性。 */
    private int count(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
