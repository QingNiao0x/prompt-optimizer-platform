package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以已存研究题答为边界回归，不将复合问题里的参数或推荐自动升级成用户确认。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class MortalityQuestionBoundaryReplayTest {
    @Test
    void identifiesTheServerCandidatesWithoutAttributingAllFourQuestionsToTheModel() throws Exception {
        JsonNode fixture = fixture();
        List<String> observed = new ObjectMapper().convertValue(fixture.path("ambiguities"), new TypeReference<>() { });
        assertThat(new AmbiguityDetector().detect(fixture.path("rawPrompt").asText()))
                .containsExactly(observed.get(0), observed.get(1));
        // 重要问题与带独立参数的复合题不能为了减少题数被整题过滤。
        assertThat(new PlanQuestionFilter().unresolvedFindings(observed, fixture.path("rawPrompt").asText(),
                emptyContext(), List.of())).containsExactlyElementsOf(observed);
    }

    @Test
    void aDenominatorAnswerDoesNotConfirmStandardizationOrItsPopulation() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("denominator",
                "死亡率的分母采用什么口径？", "死亡率分母采用年平均人口。")));
        for (String question : List.of("是否进行年龄标化？", "标准人口采用什么？", "YLL率的分母采用什么？",
                "乙地区死亡率的分母采用什么？", "2026年死亡率的分母采用什么？")) {
            assertThat(decisions.resolvesChoiceRequest(question)).as(question).isFalse();
        }
        assertThat(decisions.knownDecisions()).singleElement().satisfies(decision ->
                assertThat(decision.answer()).isEqualTo("死亡率分母采用年平均人口。"));
    }

    @Test
    void partialAnswersToTheObservedCompositeQuestionKeepBothIndependentUnknowns() throws Exception {
        String question = fixture().path("ambiguities").get(2).asText();
        String answer = "死亡率分母采用年平均人口。是否进行年龄标化尚未确定。标准人口尚未确定。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("population", question, answer)));
        assertThat(decisions.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.scope()).isEqualTo(ConfirmedPlanDecision.Scope.UNRESOLVED);
            assertThat(decision.answer()).isEqualTo(answer);
        });
        assertThat(decisions.knownDecisions()).extracting(ConfirmedPlanDecision::answer)
                .containsExactly("死亡率分母采用年平均人口。");
        assertThat(decisions.pendingDecisions()).extracting(ConfirmedPlanDecision::answer)
                .containsExactly("是否进行年龄标化尚未确定。", "标准人口尚未确定。");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "采用年龄标准化。标准人口尚未确定。",
            "采用年龄标准化。如果以后确认采用2020年标准人口，再开展对应计算。",
            "采用年龄标准化。标准人口暂不确定，请根据原始研究目标提出推荐方案并说明适用条件。"
    })
    void standardizationCanCoexistWithAPendingPopulationAndAProposal(String answer) {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("standardization", "是否进行年龄标准化？", answer)));
        assertThat(decisions.knownDecisions()).extracting(ConfirmedPlanDecision::answer)
                .containsExactly("采用年龄标准化。");
        assertThat(decisions.pendingDecisions()).isNotEmpty();
        assertThat(decisions.resolvesChoiceRequest("标准人口采用什么？")).isFalse();
    }

    @Test
    void selectingArriagaDoesNotConfirmItsTargetFactorsOrAnotherMethodsParameters() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("method", "分解方法采用什么？",
                "采用Arriaga。分解对象尚未确定。分解因素尚未确定。")));
        assertThat(decisions.knownDecisions()).extracting(ConfirmedPlanDecision::answer).containsExactly("采用Arriaga。");
        assertThat(decisions.pendingDecisions()).extracting(ConfirmedPlanDecision::answer)
                .containsExactly("分解对象尚未确定。", "分解因素尚未确定。");
        for (String question : List.of("分解对象采用什么？", "分解因素采用什么？", "Joinpoint参数采用什么？")) {
            assertThat(decisions.resolvesChoiceRequest(question)).as(question).isFalse();
        }
    }

    @Test
    void proposingAProfessionalDesignDoesNotConfirmTheActualParameter() throws Exception {
        String question = fixture().path("ambiguities").get(3).asText();
        String answer = "请根据原始研究目标提出推荐设计并说明适用条件。不确认实际分解对象。";
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("decomposition", question, answer)));
        assertThat(decisions.knownDecisions()).isEmpty();
        assertThat(decisions.pendingDecisions()).isNotEmpty();
        assertThat(PlanAnswerSemantics.independentStatements(answer)).anySatisfy(statement ->
                assertThat(statement.kind()).isEqualTo(PlanAnswerSemantics.StatementKind.DELEGATION));
    }

    private JsonNode fixture() throws Exception {
        try (var source = getClass().getResourceAsStream("/enhancement/mortality-20261016-observed.json")) {
            return new ObjectMapper().readTree(source);
        }
    }

    private ContextSnapshot emptyContext() {
        return new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
    }
}
