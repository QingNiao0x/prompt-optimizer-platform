package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原文出现待翻译术语不是保留英文的用户偏好；只移除无依据推荐，保留所有真实选项。
 * 明确当前选择和有效工程技术依据仍可支持推荐，避免为翻译修复而禁用已有技术匹配。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RecommendationTranslationEvidenceRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void doesNotTreatAnEnglishWordInTheSourceTextAsAPreferenceToKeepItInEnglish() throws Exception {
        var fixture = fixture();
        var actual = mapper.convertValue(fixture.path("questions").get(0), PlanQuestion.class);
        var digest = mapper.convertValue(fixture.path("digest"), PlanningContextDigest.class);
        var aligned = PlanRecommendationAligner.align(actual, new PlanningProviderRequest(
                fixture.path("rawPrompt").asText(), "", List.of(), digest));
        assertThat(aligned.options()).allSatisfy(option -> {
            assertThat(option.recommended()).as(option.label()).isFalse();
            assertThat(option.recommendationReason()).isEmpty();
        });
        assertThat(aligned.options()).extracting(PlanOption::id, PlanOption::label, PlanOption::description, PlanOption::answer)
                .containsExactlyElementsOf(actual.options().stream().map(option ->
                        org.assertj.core.groups.Tuple.tuple(option.id(), option.label(), option.description(), option.answer())).toList());
    }

    @Test
    void preservesAnExplicitCurrentPreferenceToKeepTheSameWordInEnglish() throws Exception {
        var fixture = fixture();
        var actual = mapper.convertValue(fixture.path("questions").get(0), PlanQuestion.class);
        var digest = mapper.convertValue(fixture.path("digest"), PlanningContextDigest.class);
        var aligned = PlanRecommendationAligner.align(actual, new PlanningProviderRequest(
                fixture.path("rawPrompt").asText() + "\n本次明确选择保留英文 access pass，并保留正式名称待确认状态。", "", List.of(), digest));
        assertThat(aligned.options()).filteredOn(PlanOption::recommended).singleElement()
                .satisfies(option -> assertThat(option.id()).isEqualTo("keep_english"));
    }

    @Test
    void preservesAnActuallyUsedFrameworkAsValidProjectRecommendationEvidence() {
        var question = new PlanQuestion("framework", "开发该界面时选哪个框架？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("vue", "Vue 3", "保持当前项目技术栈", "采用Vue 3", false),
                        new PlanOption("react", "React 18", "替换当前框架", "采用React 18", false)), List.of(), true);
        var digest = new PlanningContextDigest("", List.of("Vue 3"), List.of(), List.of(), List.of(), "COMPLETE", 1, List.of());
        var aligned = PlanRecommendationAligner.align(question, new PlanningProviderRequest("开发当前项目的订单页面。", "", List.of(), digest));
        assertThat(aligned.options()).filteredOn(PlanOption::recommended).singleElement()
                .satisfies(option -> assertThat(option.id()).isEqualTo("vue"));
    }

    private com.fasterxml.jackson.databind.JsonNode fixture() throws Exception {
        return mapper.readTree(getClass().getResourceAsStream("/plan-regression/translation-recommendation-evidence-20261005.json"));
    }
}
