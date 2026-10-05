package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放 candidate-04 的跨题未决子项与医院改写，并核对独立决定、对象和条件不被吞并。
 * 使用真实回答的最小片段，不将提醒数量下降本身视为正确。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PendingReminderIdentityRegressionTest {
    @Test
    void mergesTheScoringGradeAliasButKeepsAnIndependentAgreementMetric() {
        var result = merge(List.of(
                new PlanAnswer("scale", "评分表采用哪种评分尺度？",
                        "评分尺度采用顺序尺度。具体等级与评分锚点仍未确定，不得自行指定。"),
                new PlanAnswer("agreement", "两名评审的分歧处理与一致性评价方式应怎样预先确定？",
                        "一致性评价指标尚未确定。评分等级与评分锚点仍未确定，不得自行指定。")), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2)
                .anyMatch(text -> text.contains("一致性评价指标"));
        assertThat(result.executionPrerequisites().stream().filter(text -> text.contains("评分锚点"))).hasSize(1);
    }

    @Test
    void keepsTheIndependentAgreementDecisionWhileMergingTheRepeatedGradeAndAnchorSubitem() {
        var decisions = ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("agreement_metric", "两名评审之间的一致性，你希望用哪种方式评价？",
                        "暂不确定，不锁定一致性统计指标；采用顺序尺度，具体等级与评分锚点仍未确定。"),
                new PlanAnswer("score_scale", "评分表采用哪种评分尺度？",
                        "本次评分尺度采用顺序尺度。具体等级和评分锚点仍未确定，不得自行指定。")));
        var result = new PlanAmbiguityMerger(decisions).merge(List.of(
                "评分尺度采用顺序尺度，但具体等级与评分锚点仍未确定，不得自行指定；影响评分表设计与统计方法选择。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2)
                .anyMatch(text -> text.contains("一致性统计指标"))
                .anyMatch(text -> text.contains("评分锚点") && text.contains("不得自行指定")
                        && text.contains("影响评分表设计与统计方法选择"));
        assertThat(result.executionPrerequisites().stream().filter(text -> text.contains("评分锚点"))).hasSize(1);
    }

    @Test
    void mergesAnObservationQuestionParaphraseButRetainsItsOperationalExplanation() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("Q1",
                "对于预约后未到诊（爽约）的判定，观察窗口应如何设定？", "当前尚未决定，不得默认补全。")));
        var result = new PlanAmbiguityMerger(decisions).merge(List.of(
                "预约未到诊的观察窗口应如何定义？当前尚未决定，不得默认补全。观察窗口决定一个预约事件何时可以归为最终状态，窗口未确定前只应展示待核验事件，不应将尚未到期的预约算入爽约率。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).singleElement().asString()
                .contains("尚未到期的预约", "不应", "爽约率");
    }

    @Test
    void mergesAnUnknownDenominatorParaphraseWithoutLosingConfirmedStatusHandling() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("Q4",
                "对于到诊状态为UNKNOWN的预约事件，在爽约率等指标中应如何处理？",
                "UNKNOWN不直接计为爽约，作为待核验状态单独呈现，材料未细分原因时不归责患者；爽约率分母是否包含UNKNOWN尚未确定，不计算真实医院指标。")));
        var result = new PlanAmbiguityMerger(decisions).merge(List.of(
                "对于到诊状态为 UNKNOWN 的预约事件，爽约率分母是否包含 UNKNOWN 尚未确定，不计算真实医院指标。UNKNOWN 不直接计为爽约，作为待核验状态单独呈现，材料未细分原因时不归责患者。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).singleElement().asString()
                .contains("爽约率分母", "不计算真实医院指标", "不直接计为爽约", "不归责患者");
        assertThat(decisions.knownDecisions()).singleElement().satisfies(value ->
                assertThat(value.answer()).contains("UNKNOWN不直接计为爽约").doesNotContain("分母"));
        assertThat(PlanAnswerSemantics.pendingSubject("爽约率分母是否包含UNKNOWN尚未确定"))
                .isEqualTo("爽约率分母是否包含UNKNOWN");
        assertThat(PlanAnswerSemantics.unresolved("当前unknown。" )).isTrue();
    }

    @Test
    void mergesTheSameFullyNamedPendingSubjectAcrossQuestionIdsAndRetainsBothExplanations() {
        var result = merge(List.of(new PlanAnswer("source", "订单审批标准来源是什么？", "订单审批标准尚未确定，需核对来源。"),
                new PlanAnswer("choice", "订单审批标准选哪份？", "订单审批标准尚未确定，需保留未批准状态。")), List.of());
        assertThat(result.executionPrerequisites()).singleElement().asString().contains("核对来源", "保留未批准状态");
    }

    @Test
    void keepsTheSameGenericAttributeForDifferentNamedObjectsSeparate() {
        var result = merge(List.of(new PlanAnswer("a", "甲医院评分尺度采用哪种？", "具体等级和评分锚点仍未确定。"),
                new PlanAnswer("b", "乙医院评分尺度采用哪种？", "具体等级和评分锚点仍未确定。")), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2);
    }

    @Test
    void doesNotBorrowAnotherHospitalsKnownPrefixToHideItsNewPendingDecision() {
        var answers = List.of(
                new PlanAnswer("a", "对于甲医院，评分尺度与评分锚点如何确定？",
                        "甲医院采用五级尺度。等级和评分锚点尚未确定。"),
                new PlanAnswer("b", "对于乙医院，评分尺度如何选择？", "乙医院采用三级尺度。"));
        String finding = "乙医院采用三级尺度，等级和评分锚点尚未确定。";
        assertThat(merge(answers, List.of(finding)).executionPrerequisites())
                .hasSize(2).contains(finding).anyMatch(text -> text.contains("甲医院"));
    }

    @Test
    void preservesAnExplicitNewHospitalWhenThePendingPropertyIsOtherwiseIdentical() {
        var answers = List.of(new PlanAnswer("a", "对于甲医院，评分尺度与评分锚点如何确定？",
                "甲医院采用五级尺度。等级和评分锚点尚未确定。"));
        String finding = "对于乙医院，等级和评分锚点尚未确定。";
        assertThat(merge(answers, List.of(finding)).executionPrerequisites()).hasSize(2).contains(finding);
    }

    @Test
    void stillMergesTheKnownPrefixFromTheSameBoundObject() {
        var answers = List.of(new PlanAnswer("a", "对于甲医院，评分尺度与评分锚点如何确定？",
                "甲医院采用五级尺度。等级和评分锚点尚未确定。"));
        assertThat(merge(answers, List.of("甲医院采用五级尺度，等级和评分锚点尚未确定。"))
                .executionPrerequisites()).singleElement().asString().contains("甲医院", "评分锚点");
    }

    @Test
    void keepsInheritedNumericConditionsInTheIdentityAndTheVisibleReminder() {
        var result = merge(List.of(new PlanAnswer("low", "对于订单金额>=3000元时，审批标准是什么？", "审批标准尚未确定。"),
                new PlanAnswer("high", "对于订单金额>=5000元时，审批标准是什么？", "审批标准尚未确定。")), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2)
                .anyMatch(text -> text.contains(">=3000")).anyMatch(text -> text.contains(">=5000"));
    }

    @Test
    void keepsDifferentBusinessObjectsWithoutAnInstitutionSuffixSeparate() {
        var result = merge(List.of(new PlanAnswer("order", "订单评分尺度采用哪种？", "具体等级和评分锚点仍未确定。"),
                new PlanAnswer("refund", "退款评分尺度采用哪种？", "具体等级和评分锚点仍未确定。")), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2)
                .anyMatch(text -> text.contains("订单")).anyMatch(text -> text.contains("退款"));
    }

    @Test
    void keepsNewObjectsConditionsComparatorsAndAdditionalPendingAttributes() {
        var answers = List.of(new PlanAnswer("a", "订单审批标准是什么？", "订单审批标准尚未确定。"));
        for (String changed : List.of("退款审批标准尚未确定。", "订单审批标准在跨境时尚未确定。",
                "订单审批标准金额>=3000元时尚未确定。", "订单审批标准金额>3000元时尚未确定。",
                "订单审批标准和有效期尚未确定。")) {
            assertThat(merge(answers, List.of(changed)).executionPrerequisites()).as(changed).hasSize(2).contains(changed);
        }
    }

    @Test
    void keepsAnotherChoiceInTheTailOfTheSameObservationQuestion() {
        var result = merge(List.of(new PlanAnswer("Q1", "对于预约后未到诊（爽约）的判定，观察窗口应如何设定？",
                "当前尚未决定，不得默认补全。")), List.of(
                "预约未到诊的观察窗口应如何定义？另外，退款观察窗口是否计入节假日尚未确定。"));
        assertThat(result.executionPrerequisites()).hasSize(2).anyMatch(text -> text.contains("退款观察窗口"));
    }

    @Test
    void doesNotMergeBareUnknownAnswersFromDifferentQuestions() {
        assertThat(merge(List.of(new PlanAnswer("a", "研究观察窗口应如何确定？", "暂不确定。"),
                new PlanAnswer("b", "退款观察窗口应如何确定？", "暂不确定。")), List.of())
                .executionPrerequisites()).hasSize(2);
    }

    @Test
    void doesNotHideANewPendingAttributeWithoutAnAdditionalQuestionPreamble() {
        var finding = "订单审批标准尚未确定。审批生效时间尚未确定。";
        assertThat(merge(List.of(new PlanAnswer("approval", "订单审批标准是什么？", "订单审批标准尚未确定。")),
                List.of(finding)).executionPrerequisites()).hasSize(2).contains(finding);
    }

    @Test
    void neverChangesComparatorsOrCodeCaseWhenGivingNamedSubitemsCrossQuestionKeys() {
        assertThat(merge(List.of(new PlanAnswer("gt", "条件金额>3000元时订单审批标准是什么？",
                        "条件金额>3000元时订单审批标准尚未确定。"),
                new PlanAnswer("gte", "条件金额>=3000元时订单审批标准是什么？",
                        "条件金额>=3000元时订单审批标准尚未确定。")), List.of()).executionPrerequisites())
                .hasSize(2).anyMatch(text -> text.contains(">3000")).anyMatch(text -> text.contains(">=3000"));
        assertThat(merge(List.of(new PlanAnswer("upper", "STATUS_A映射是什么？", "STATUS_A映射尚未确定。"),
                new PlanAnswer("lower", "status_a映射是什么？", "status_a映射尚未确定。")), List.of())
                .executionPrerequisites()).hasSize(2);
    }

    @Test
    void preservesTwoIndependentPendingClausesAfterALeadingUnknownAnswer() {
        assertThat(PlanAnswerSemantics.pendingParts("暂不确定，不锁定一致性统计指标；具体等级和评分锚点仍未确定。"))
                .hasSize(2).anyMatch(text -> text.contains("一致性统计指标"));
        assertThat(PlanAnswerSemantics.pendingParts("暂不确定。具体等级和评分锚点仍未确定。"))
                .singleElement().asString().contains("评分锚点");
    }

    private PlanAmbiguityMerger.MergeResult merge(List<PlanAnswer> answers, List<String> findings) {
        return new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(findings, List.of(), List.of());
    }
}
