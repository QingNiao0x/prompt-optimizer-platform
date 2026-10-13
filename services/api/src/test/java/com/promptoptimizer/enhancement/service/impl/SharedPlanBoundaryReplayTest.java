package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用候选六真实响应核对共同边界已有正文时不逐题复写，原问题和答案继续留存。
 * @author QingNiao
 * @since 0.1.0
 */
class SharedPlanBoundaryReplayTest {
    @Test void coveredSharedBoundaryKeepsEveryQuestionWithoutRepeatingItsExplanation() throws Exception {
        var json = new ObjectMapper();
        var fixture = json.readTree(getClass().getResourceAsStream("/enhancement/shared-refusal-candidate6.json"));
        var source = fixture.path("providerResponse");
        var answers = List.of(json.treeToValue(fixture.path("answers"), PlanAnswer[].class));
        var response = new EnhancementProviderResponse(
                List.of(json.treeToValue(source.path("sections"), PromptSection[].class)), "test", "test", false,
                List.of(json.treeToValue(source.path("ambiguities"), String[].class)), List.of(),
                json.treeToValue(source.path("assessment"), TaskEnhancementAssessment.class));
        var result = new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "按原文交付。", "保留原意。", "遵守示例选择。"),
                List.of(), answers, true, List.of(), false, 1, fixture.path("rawPrompt").asText());
        var bound = result.ambiguities().stream().filter(value -> value.startsWith("该问题尚未确定：")).toList();
        assertThat(bound).hasSize(answers.size()).allSatisfy(value ->
                assertThat(value).doesNotContain("用户说明：不确认任何既有参数"));
        for (var answer : answers) assertThat(String.join("\n", bound)).contains(answer.question());
        assertThat(result.optimizedPrompt()).contains("不确认任何既有参数");
        assertThat(EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO,
                answers, List.of(), false).confirmedDecisions()).extracting(value -> value.answer())
                .containsExactlyElementsOf(answers.stream().map(PlanAnswer::answer).toList());
    }

    @Test void uncoveredScopedAndConditionalExplanationsRemainWithTheirOwnQuestions() {
        var answers = List.of(new PlanAnswer("a", "甲院2026年的阈值是什么？", "不确认甲院2026年的阈值。"),
                new PlanAnswer("b", "乙院2025年的阈值是什么？", "如果以后确认乙院阈值，再开展计算。当前尚未确定。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers), "制定两院方案。")
                .merge(List.of(), List.of(), List.of());
        assertThat(String.join("\n", result.executionPrerequisites()))
                .contains("甲院2026年", "不确认甲院2026年的阈值", "乙院2025年", "如果以后确认乙院阈值");
    }

    @Test void aReversedProviderBoundaryCannotCoverTheUserExplanation() throws Exception {
        var json = new ObjectMapper();
        var fixture = json.readTree(getClass().getResourceAsStream("/enhancement/shared-refusal-candidate6.json"));
        var source = fixture.path("providerResponse");
        var answers = List.of(json.treeToValue(fixture.path("answers"), PlanAnswer[].class));
        var sections = List.of(json.treeToValue(source.path("sections"), PromptSection[].class)).stream()
                .map(section -> new PromptSection(section.type(), section.title(),
                        section.content().replace("不确认任何既有参数", "不能不确认任何既有参数"))).toList();
        var response = new EnhancementProviderResponse(sections, "test", "test", false,
                List.of(), List.of(), json.treeToValue(source.path("assessment"), TaskEnhancementAssessment.class));
        var result = new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "按原文交付。", "保留原意。", "遵守示例选择。"),
                List.of(), answers, true, List.of(), false, 1, fixture.path("rawPrompt").asText());
        assertThat(result.ambiguities().stream().filter(value -> value.startsWith("该问题尚未确定：")))
                .hasSize(answers.size()).allSatisfy(value ->
                        assertThat(value).contains("用户说明：不确认任何既有参数"));
    }

    @Test void currentUncertaintyForAnotherObjectKeepsItsOwnIdentity() {
        var answers = List.of(new PlanAnswer("a", "甲院2026年的阈值是什么？",
                "甲院2026年阈值采用5。当前乙院2025年阈值尚未确定。"));
        assertThat(ConfirmedDecisionSet.from(answers).pendingDecisions()).singleElement().satisfies(value ->
                assertThat(value.question()).contains("乙院2025年").doesNotContain("甲院2026年"));
    }

    @Test void aConditionalForAnotherObjectCannotInheritTheOriginalBinding() {
        var answers = List.of(new PlanAnswer("a", "甲院2026年的阈值是什么？",
                "甲院2026年阈值采用5。如果以后确认乙院2025年阈值，再开展计算。"));
        assertThat(ConfirmedDecisionSet.from(answers).pendingDecisions()).singleElement().satisfies(value ->
                assertThat(value.question()).contains("乙院2025年").doesNotContain("甲院2026年"));
    }

    @Test void aConditionalCannotReplaceAnExplicitDifferentYear() {
        var answers = List.of(new PlanAnswer("a", "乙院2025年的阈值是什么？",
                "乙院2025年阈值采用5。如果以后确认乙院2026年阈值，再开展计算。"));
        assertThat(ConfirmedDecisionSet.from(answers).pendingDecisions()).singleElement().satisfies(value ->
                assertThat(value.question()).contains("乙院2026年").doesNotContain("乙院2025年"));
    }

    @Test void anOmittedYearAndVersionForTheSameObjectKeepTheBoundQualifiers() {
        var answers = List.of(new PlanAnswer("a", "乙院2025年v2.1阈值是什么？",
                "如果以后确认乙院阈值，再开展计算。"));
        assertThat(ConfirmedDecisionSet.from(answers).pendingDecisions()).singleElement().satisfies(value -> {
            assertThat(value.question()).contains("乙院2025年v2.1");
            assertThat(value.answer()).contains("如果以后确认乙院阈值");
        });
    }

    @Test void aConditionalCannotReplaceAnExplicitDifferentVersion() {
        var answers = List.of(new PlanAnswer("a", "乙院2025年v2.1阈值是什么？",
                "乙院2025年v2.1阈值采用5。如果以后确认乙院2025年v2.2阈值，再开展计算。"));
        assertThat(ConfirmedDecisionSet.from(answers).pendingDecisions()).singleElement().satisfies(value ->
                assertThat(value.question()).contains("乙院2025年v2.2").doesNotContain("v2.1"));
    }
}
