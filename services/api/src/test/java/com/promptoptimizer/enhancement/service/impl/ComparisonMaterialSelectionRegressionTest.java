package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放只读对照已明确不择一之后，模型仍要求选定订单和退款阈值的真实问题。
 * 仅继承同一资料对象、属性和成对取值，不将执行任务或新增业务条件当作排版委派。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ComparisonMaterialSelectionRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void inheritsTheExistingNoSelectionDecisionAcrossBothRealModelWordings() throws Exception {
        var fixture = fixture();
        for (var replay : fixture.path("replays")) {
            var digest = mapper.convertValue(replay.path("digest"), PlanningContextDigest.class);
            var policy = ComparisonMaterialDecision.from(new PlanningProviderRequest(
                    fixture.path("rawPrompt").asText(), "", List.of(), digest));
            assertThat(questions(replay.path("questions"))).hasSize(2).allSatisfy(question ->
                    assertThat(policy.delegatedPresentation(question)).as(question.question()).isTrue());
        }
    }

    @Test
    void usesTheBusinessMeaningRatherThanRealQuestionIds() throws Exception {
        var fixture = fixture();
        var replay = fixture.path("replays").get(0);
        var digest = mapper.convertValue(replay.path("digest"), PlanningContextDigest.class);
        var policy = ComparisonMaterialDecision.from(new PlanningProviderRequest(
                fixture.path("rawPrompt").asText(), "", List.of(), digest));
        for (var question : questions(replay.path("questions"))) {
            var renamed = new PlanQuestion("unrelated-id", question.question(), question.hint(), question.type(),
                    question.options(), question.examples(), question.allowCustomAnswer());
            assertThat(policy.delegatedPresentation(renamed)).as(question.question()).isTrue();
        }
    }

    @Test
    void retainsThirdValuesDifferentComparatorsAndParametersBorrowedFromAnotherObject() throws Exception {
        var fixture = fixture();
        var replay = fixture.path("replays").get(0);
        var digest = mapper.convertValue(replay.path("digest"), PlanningContextDigest.class);
        var policy = ComparisonMaterialDecision.from(new PlanningProviderRequest(
                fixture.path("rawPrompt").asText(), "", List.of(), digest));
        List<PlanQuestion> actual = questions(replay.path("questions"));
        for (String condition : List.of("订单审批金额>3000元。", "订单审批金额>=6000元。", "订单审批金额>500元。")) {
            assertThat(policy.delegatedPresentation(withCondition(actual.getFirst(), condition))).as(condition).isFalse();
        }
        for (String condition : List.of("退款金额>=3000元。", "退款金额>3000元。", "退款金额5000元。")) {
            assertThat(policy.delegatedPresentation(withCondition(actual.getLast(), condition))).as(condition).isFalse();
        }
    }

    @Test
    void retainsNewAuthorizationConditionsAndIndependentDecisionProperties() throws Exception {
        var fixture = fixture();
        var replay = fixture.path("replays").get(0);
        var digest = mapper.convertValue(replay.path("digest"), PlanningContextDigest.class);
        var policy = ComparisonMaterialDecision.from(new PlanningProviderRequest(
                fixture.path("rawPrompt").asText(), "", List.of(), digest));
        var actual = questions(replay.path("questions")).getFirst();
        for (String condition : List.of("跨境订单适用哪组阈值？", "订单审批负责人应由谁担任？",
                "是否授权跨工作区读取？", "审批通过率分母是否包含撤销订单？", "金额币种是否为人民币？",
                "规则生效日期应如何确定？", "税前与含税金额如何适用？", "仅周末订单采用该阈值？")) {
            assertThat(policy.delegatedPresentation(withCondition(actual, condition))).as(condition).isFalse();
        }
    }

    @Test
    void doesNotInheritASelectionForExecutionOrApprovalTasks() throws Exception {
        var fixture = fixture();
        var replay = fixture.path("replays").get(0);
        var digest = mapper.convertValue(replay.path("digest"), PlanningContextDigest.class);
        var actual = questions(replay.path("questions")).getFirst();
        for (String raw : List.of("请按方案实现订单审批流程并确认采用哪个阈值。",
                fixture.path("rawPrompt").asText() + "本次必须选定执行的订单审批规则。")) {
            var policy = ComparisonMaterialDecision.from(new PlanningProviderRequest(raw, "", List.of(), digest));
            assertThat(policy.delegatedPresentation(actual)).as(raw).isFalse();
        }
    }

    @Test
    void keepsUnboundObjectsAndANewPropertyEvenWhenTheirNumbersMatchExistingMaterial() throws Exception {
        var fixture = fixture();
        var replay = fixture.path("replays").get(0);
        var digest = mapper.convertValue(replay.path("digest"), PlanningContextDigest.class);
        var policy = ComparisonMaterialDecision.from(new PlanningProviderRequest(
                fixture.path("rawPrompt").asText(), "", List.of(), digest));
        var actual = questions(replay.path("questions")).getFirst();
        var shipping = new PlanQuestion("shipping", "运费减免金额条件应采用哪个方案？", actual.hint(), actual.type(),
                actual.options(), actual.examples(), actual.allowCustomAnswer());
        var dailyLimit = new PlanQuestion("daily-limit", "订单审批每日处理数量应采用哪个阈值？", actual.hint(), actual.type(),
                actual.options(), actual.examples(), actual.allowCustomAnswer());
        assertThat(policy.delegatedPresentation(shipping)).isFalse();
        assertThat(policy.delegatedPresentation(dailyLimit)).isFalse();
    }

    /** 将真实题的说明追加新条件，其余完整选项不变，验证保护不会依赖模型题号。 */
    private static PlanQuestion withCondition(PlanQuestion question, String condition) {
        return new PlanQuestion(question.id(), question.question(), question.hint() + "。" + condition,
                question.type(), question.options(), question.examples(), question.allowCustomAnswer());
    }

    private com.fasterxml.jackson.databind.JsonNode fixture() throws Exception {
        return mapper.readTree(getClass().getResourceAsStream("/plan-regression/comparison-material-selection-20261005.json"));
    }

    private List<PlanQuestion> questions(com.fasterxml.jackson.databind.JsonNode values) {
        return mapper.convertValue(values, mapper.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class));
    }
}
