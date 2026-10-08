package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放真实模型对完整专业参数的问句与短状态，未知继续可见而不复写同一决定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class BoundProfessionalReminderTest {
    private static final String RAW = "甲院2025年观察起点未提供；甲院2025年观察终点未确认；"
            + "甲院2025年观察日期范围尚未确定；甲院2025年异常等待阈值尚未确定。";

    @Test
    void removesOnlyTheRepeatedPureStateOfTheSameCompleteParameter() {
        for (var pair : List.of(
                List.of("甲院2025年观察窗口的起点事件是什么？", "甲院2025年的观察起点尚未确定。"),
                List.of("甲院2025年观察窗口的终点事件是什么？", "甲院2025年的观察终点尚未确定。"),
                List.of("甲院2025年观察窗口的起点应如何确定？", "甲院2025年的观察起点尚未确定。"),
                List.of("甲院2025年观察窗口的终点应如何确定？", "甲院2025年的观察终点尚未确定。"),
                List.of("甲院2025年观察的日期范围是什么？", "甲院2025年观察日期范围尚未确定。"),
                List.of("甲院2025年异常等待阈值的具体数值和单位是什么？", "甲院2025年异常等待的阈值尚未确定。"),
                List.of("跨字段逻辑一致性指标的统计分母是什么？", "跨字段一致性指标的分母尚未确定。"))) {
            var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("parameter", pair.getFirst(), "暂不确定。")));
            var merged = new PlanAmbiguityMerger(decisions, RAW + "跨字段一致性指标分母尚未确定。")
                    .merge(List.of(pair.get(1)), List.of(), List.of());
            assertThat(merged.messages()).as(pair.getFirst()).hasSize(1).allSatisfy(message ->
                    assertThat(message).doesNotContain("补充说明："));
        }
    }

    @Test
    void keepsAnotherInstitutionYearNewPropertyAndNewApprovalCondition() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("start", "甲院2025年观察窗口的起点事件是什么？", "暂不确定。")));
        List<String> additions = List.of("乙院2025年的观察起点尚未确定。", "甲院2024年的观察起点尚未确定。",
                "甲院2025年的观察终点尚未确定。", "甲院2025年的观察起点尚未确定；还需确认新增科室共享记录的授权范围。");
        var merged = new PlanAmbiguityMerger(decisions, RAW).merge(additions, List.of(), List.of());
        assertThat(String.join("\n", merged.executionPrerequisites())).contains("乙院2025年", "甲院2024年", "观察终点", "新增科室共享记录的授权范围");
    }

    @Test
    void keepsQuotesHypothesesAndChangedComparisonValues() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("threshold", "甲院2025年异常等待阈值是什么？", "暂不确定。")));
        var additions = List.of("如果以后确认甲院2025年异常等待阈值为90分钟，再调整方案。",
                "甲院2025年异常等待阈值>=90分钟还是>90分钟尚未确定。", "> 乙院2025年异常等待阈值尚未确定。");
        assertThat(String.join("\n", new PlanAmbiguityMerger(decisions, RAW).merge(additions, List.of(), List.of()).executionPrerequisites()))
                .contains("如果以后确认", ">=90", ">90", "乙院2025年");
    }
}
