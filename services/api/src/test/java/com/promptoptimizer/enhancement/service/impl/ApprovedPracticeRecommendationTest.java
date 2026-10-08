package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放真实模型的较长候选，批准的同范围实践可推荐，未审批及其他对象不得借用数值。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ApprovedPracticeRecommendationTest {
    private static final String RAW = "为甲院2025年拟定观察方案，请让我选择观察窗口。异常等待阈值仍未确定。";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bothRealModelOptionsUseTheApprovedMatchingWindowAsEvidence() throws Exception {
        try (var stream = getClass().getResourceAsStream("/enhancement/approved-window-actual-20261008.json")) {
            for (var record : mapper.readTree(stream)) {
                var question = mapper.treeToValue(record.get("question"), PlanQuestion.class);
                var digest = mapper.treeToValue(record.get("digest"), PlanningContextDigest.class);
                assertThat(PlanRecommendationAligner.align(question,
                        new PlanningProviderRequest(RAW, "", List.of(), digest)).options())
                        .as(record.get("model").asText()).filteredOn(PlanOption::recommended).singleElement()
                        .satisfies(option -> {
                            assertThat(option.id()).isEqualTo("use_24h");
                            assertThat(option.recommendationReason()).contains("项目证据", "24小时", "建议");
                        });
            }
        }
    }

    @Test
    void approvalMustMatchInstitutionYearAndCurrentState() {
        for (String evidence : List.of("乙院2025年24小时观察窗口已批准并生效。",
                "甲院2024年24小时观察窗口已批准并生效。",
                "甲院2025年24小时观察窗口尚未批准。",
                "如果以后确认甲院2025年24小时观察窗口已批准，再考虑采用。",
                "甲院2025年24小时观察窗口已批准并生效。该规则尚未核验，适用范围待确认。")) {
            assertThat(PlanRecommendationAligner.align(question(), input(RAW, evidence)).options())
                    .as(evidence).noneMatch(PlanOption::recommended);
        }
    }

    @Test
    void twoApprovedChoicesAndComparisonOnlyDoNotForceARecommendation() {
        String approved = "甲院2025年24小时观察窗口已批准并生效。甲院2025年48小时观察窗口也已批准并生效。";
        assertThat(PlanRecommendationAligner.align(question(), input(RAW, approved)).options())
                .noneMatch(PlanOption::recommended);
        assertThat(PlanRecommendationAligner.align(question(), input(
                RAW + "只比较24小时和48小时观察窗口，不得选择其中一项作为本次口径。", approved)).options())
                .noneMatch(PlanOption::recommended);
    }

    @Test
    void anotherMetricWithTheSameQuantityDoesNotSupportTheWindow() {
        assertThat(PlanRecommendationAligner.align(question(), input(RAW,
                "甲院2025年等待阈值24小时已批准并生效。观察窗口尚未确定。")).options())
                .noneMatch(PlanOption::recommended);
    }

    private static PlanningProviderRequest input(String raw, String evidence) {
        return new PlanningProviderRequest(raw, "", List.of(), new PlanningContextDigest("", List.of(),
                List.of(), List.of(), List.of(evidence), "COMPLETE", 1, List.of()));
    }

    private static PlanQuestion question() {
        return new PlanQuestion("window", "观察窗口采用哪种方案？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("24", "沿用已批准的24小时窗口", "沿用甲院2025年24小时观察窗口。",
                                "观察窗口采用甲院2025年已批准的24小时窗口。", false),
                        new PlanOption("48", "沿用已批准的48小时窗口", "沿用甲院2025年48小时观察窗口。",
                                "观察窗口采用甲院2025年已批准的48小时窗口。", false)), List.of(), true);
    }
}
