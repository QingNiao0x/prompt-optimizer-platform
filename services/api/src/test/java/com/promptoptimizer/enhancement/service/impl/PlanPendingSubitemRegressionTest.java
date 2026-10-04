package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定真实医院 S7 和二次金额反例，验证同题未决子项独立绑定及新对象防误删。
 * 不发送评审卡给 Provider，也不以减少提醒数替代业务内容完整性。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanPendingSubitemRegressionTest {
    private static final String QUESTION = "乙医院预约状态取值与甲医院BOOKED、CANCELLED、ATTENDED之间应如何映射？";
    private static final String ANSWER = "乙医院BOOKED表示已预约，CANCELLED表示已取消，ATTENDED表示已到诊，这三项已确认；其余状态映射暂不确定。乙医院列名和业务含义暂不确定，保留待收集信息，不计算跨院一致到诊率。";
    private static final String STATE = "乙医院预约状态中除 BOOKED、CANCELLED、ATTENDED 之外的其他取值应如何映射？其余状态映射暂不确定，影响跨院状态口径与到诊率计算。";
    private static final String COLUMN = "乙医院列名及其业务含义尚未提供，影响字段对照表与跨院一致性检查。";
    private static final String OLD_CONFLICT = "资料对“审批金额阈值”存在不同取值：docs/现行审批.md（30000元）与 docs/候选审批方案.md（50000元）。请确认本次采用哪一项。";
    private static final String NEW_CONFLICT = "资料对“审批金额阈值”存在不同取值：docs/候选审批方案.md（50000元）与 docs/二次收到的审批意见.md（80000元）。请确认本次采用哪一项。";
    private static final String NEW_DETAIL = "docs/二次收到的审批意见.md 提出审批金额阈值为 80000 元，并注明该意见与候选方案冲突、尚未经业务负责人批准；本次已确认采用 50000 元，请确认该 80000 元意见是否仍需在方案中作为待批准事项保留或说明其适用范围。";
    private static final String DELIVERY_RAW = "跨院原始记录共享：未批准。必须各院分别处理、仅交付不可识别的统计结构。";

    @Test
    void shouldMergeEachCapturedSubitemIntoItsOwnPendingDecision() {
        var merged = hospital(List.of(STATE, COLUMN));
        assertThat(merged.executionPrerequisites()).hasSize(2)
                .anyMatch(item -> item.contains("其余状态映射暂不确定") && item.contains("影响跨院状态口径"))
                .anyMatch(item -> item.contains("乙医院列名和业务含义暂不确定") && item.contains("影响字段对照表"));
    }

    @Test
    void shouldMergeTheCapturedShorterStateQuestionAndKeepItsFullExplanation() {
        String reminder = "乙医院除 BOOKED、CANCELLED、ATTENDED 之外的状态如何映射？这三项已确认，其余状态映射暂不确定，需收集完整状态清单及业务含义，否则无法统一到诊口径。";
        assertThat(hospital(List.of(reminder)).executionPrerequisites()).hasSize(2)
                .anyMatch(item -> item.contains("状态映射暂不确定") && item.contains("需收集完整状态清单及业务含义"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"丙医院列名及其业务含义尚未提供，影响字段对照表。",
            "乙医院2026年列名及其业务含义尚未提供，影响字段对照表。",
            "乙医院列名及其计费业务含义尚未提供，影响字段对照表。",
            "乙医院预约状态中除 BOOKED、CANCELLED、ATTENDED_EXTRA 之外的其他取值应如何映射？其余状态映射暂不确定。"})
    void shouldKeepEveryNewObjectConditionAttributeAndCode(String reminder) {
        assertThat(hospital(List.of(reminder)).executionPrerequisites()).hasSize(3).contains(reminder);
    }

    @Test
    void shouldKeepBothSubitemsWhenTheProviderUsesTheOriginalQuestionId() {
        var result = hospital(List.of(COLUMN, STATE));
        assertThat(result.executionPrerequisites()).hasSize(2);
        assertThat(result.executionPrerequisites().getFirst()).contains("状态映射").doesNotContain("影响字段对照表");
        assertThat(result.executionPrerequisites().getLast()).contains("列名").doesNotContain("影响跨院状态口径");
    }

    @Test
    void shouldMergeTheNewSourceExplanationWithoutResolvingItsConflict() {
        var result = conflict(NEW_DETAIL);
        assertThat(result.executionPrerequisites()).singleElement().asString()
                .contains("50000元", "80000元", "尚未经业务负责人批准", "适用范围");
    }

    @ParameterizedTest
    @ValueSource(strings = {"90000", "2026年", "退款", "docs/第三份意见.md"})
    void shouldKeepNewEvidenceOrAnIndependentDecisionAfterTheSameConflict(String extra) {
        String detail = NEW_DETAIL + "另外，" + extra + "适用范围尚未确定，需确认。";
        assertThat(conflict(detail).executionPrerequisites()).hasSize(2).contains(detail);
    }

    @Test
    void shouldNotAssociateANewSourceUsingOnlyTheAmountPair() {
        String detail = NEW_DETAIL.replace("docs/二次收到的审批意见.md", "docs/第三份意见.md");
        assertThat(conflict(detail).executionPrerequisites()).hasSize(2).contains(detail);
    }

    @Test
    void shouldInheritTheAlreadySpecifiedSeparateStatisticsDelivery() {
        assertThat(filter(DELIVERY_RAW, deliveryQuestion("", false))).isEmpty();
        assertThat(filter(DELIVERY_RAW, deliveryQuestion("跨院共享原始记录尚未获批准，不能把技术上可合并理解为已获授权；这决定最终交付物是各院分别处理还是仅交付不可识别的统计结构。", false))).isEmpty();
    }

    @Test
    void shouldKeepAHiddenNewDecisionInAnOptionDescription() {
        var original = deliveryQuestion("", false);
        var first = original.options().getFirst();
        var candidate = new PlanQuestion(original.id(), original.question(), original.hint(), original.type(), List.of(
                new PlanOption(first.id(), first.label(), "另需确认第三家医院的状态定义。", first.answer(), false, ""),
                original.options().getLast()), List.of(), true);
        assertThat(filter(DELIVERY_RAW, candidate)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"另需确认第三家医院的数据是否纳入。", "汇总统计的保留期尚未确定。",
            "未来跨院共享申请的有效期需确认。", "另需选择新增的满意度调查指标。"})
    void shouldKeepANewScopeOrBusinessDecisionInsideDeliveryMetadata(String hint) {
        assertThat(filter(DELIVERY_RAW, deliveryQuestion(hint, false))).hasSize(1);
    }

    @Test
    void shouldKeepTheDeliveryQuestionWhenUserExplicitlyAsksToChoose() {
        assertThat(filter(DELIVERY_RAW + "请让我选择本次汇总结果的交付方式。", deliveryQuestion("", false))).hasSize(1);
    }

    @Test
    void shouldKeepAnUnspecifiedDeliveryWithoutPromotingApprovalIntoAuthorization() {
        assertThat(filter("跨院原始记录共享：未批准。", deliveryQuestion("", false))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"空单元格统一填0", "当前方案待确认，空单元格统一填0。", "将空单元格全部补为0。"})
    void shouldRejectTheCapturedMissingToZeroCandidate(String candidate) {
        assertThatThrownBy(() -> validate("空单元格不能统一填0。", candidate))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"空单元格不得统一填0。", "空单元格保持缺失，已有数值0保留。", "空单元格是否可填0？"})
    void shouldKeepLegalMissingRulesAndQuestions(String candidate) {
        assertThatCode(() -> validate("空单元格不能统一填0。", candidate)).doesNotThrowAnyException();
    }

    @Test
    void shouldNotApplyOneHospitalsMissingRuleToAnotherHospital() {
        assertThatCode(() -> validate("甲医院空单元格不能统一填0。", "乙医院空单元格统一填0。")).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate("甲医院空单元格不能统一填0。", "甲医院空单元格统一填0。"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    private PlanAmbiguityMerger.MergeResult hospital(List<String> findings) {
        return new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of(new PlanAnswer("q2", QUESTION, ANSWER))))
                .merge(findings, List.of(), List.of());
    }

    private PlanAmbiguityMerger.MergeResult conflict(String detail) {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("context-conflict-1", OLD_CONFLICT,
                "本次采用：50000元。等于50000元不触发财务复核。")));
        return new PlanAmbiguityMerger(decisions).merge(List.of(NEW_CONFLICT, detail), List.of(NEW_CONFLICT), List.of());
    }

    private List<PlanQuestion> filter(String raw, PlanQuestion question) {
        var input = new PlanningProviderRequest(raw, "", List.of());
        return new PlanQuestionFilter().filter(List.of(question), input);
    }

    private void validate(String raw, String candidate) {
        var guard = new RequirementFidelityGuard();
        guard.validate(candidate, guard.explicitRules(raw, List.of()), "questions.options.answer");
    }

    private PlanQuestion deliveryQuestion(String hint, boolean recommended) {
        return new PlanQuestion("q3", "在跨院原始记录共享尚未获批准的情况下，本次交付的汇总结果应如何处理？", hint,
                PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("separate", "各院分别处理", "不合并原始记录。", "各院分别处理，不合并原始记录，仅各自输出检查结果。", recommended, ""),
                new PlanOption("aggregate", "不可识别统计结构", "不共享原始记录。", "不共享原始记录，仅交付各院分别处理后的不可识别统计结构。", false, "")), List.of(), true);
    }
}
