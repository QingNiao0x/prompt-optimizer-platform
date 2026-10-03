package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放最终候选的论文和数据分析提醒；归并题干时保留解释及新的独立决定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class LatestFidelityReplayTest {
    @Test
    void shouldGroupTheRealModalDenominatorReminderWithoutDroppingItsAlternatives() {
        String finding = "完成率的分母应采用“全部报名人数”还是“有效答卷人数”尚未确定；在确认前不得擅自选择口径或计算完成率。";
        var answers = List.of(new PlanAnswer("denominator", "完成率的分母应采用哪个口径？", "暂不确定"));
        var merged = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers))
                .merge(List.of(finding), List.of(), List.of());
        assertThat(merged.messages()).hasSize(1);
        assertThat(String.join("\n", merged.executionPrerequisites())).contains(finding);
    }

    @Test
    void shouldNotFoldNewValuesOrNewSubjectsIntoAPartiallyAnsweredQuestion() {
        var answers = List.of(new PlanAnswer("output", "输出指标及阈值是什么？",
                "使用 50000 元阈值；输出指标暂不确定。"));
        for (String finding : List.of("输出指标与 5000 元阈值尚未确定：需要确认新金额。",
                "退款订单输出指标尚未确定：需要确认适用对象。")) {
            var merged = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(List.of(finding), List.of(), List.of());
            assertThat(merged.messages()).hasSize(2).contains(finding);
        }
    }
    @Test
    void shouldMergeCommaExplanationsAndEquivalentQuestionGrammar() {
        var answers = List.of(
                new PlanAnswer("source", "20 个任务（代码开发、机关报告、科研方案、数据分析各 5 个）的来源如何确定？", "暂不确定"),
                new PlanAnswer("difficulty", "四类任务内部是否控制难度或复杂度？", "暂不确定"),
                new PlanAnswer("quality", "“提示词质量”用什么指标衡量？", "暂不确定"),
                new PlanAnswer("agreement", "两名独立评审的评分一致性计划如何报告？", "暂不确定"));
        var findings = List.of(
                "20 个任务（代码开发、机关报告、科研方案、数据分析各 5 个）的具体来源尚未确定，影响任务材料清单与可复现性。",
                "四类任务内部是否控制难度或复杂度尚未确定，影响任务间可比性与分层分析口径。",
                "“提示词质量”的衡量指标尚未确定，影响评审量表、评分维度与后续统计对象。",
                "两名独立评审的评分一致性计划如何报告尚未确定，影响一致性统计量的选择与呈现方式。");
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(findings, List.of(), List.of());
        assertThat(result.messages()).hasSize(4);
        findings.forEach(finding -> assertThat(String.join("\n", result.executionPrerequisites()))
                .contains(finding.substring(finding.indexOf('，') + 1)));
    }

    @Test
    void shouldRetainStatisticsDetailsAndKeepAnIndependentDecisionSeparate() {
        String question = "失败记录单列时，需要统计哪些指标？";
        String repeated = "失败记录单列时需统计哪些指标尚未确定：materials/requests.csv 中 FAILED 记录含 latency_ms=8000，但未明确失败侧需要输出哪些统计量（如失败计数、失败时延分布、按 cached 分组等），会影响失败记录单列部分的输出内容。";
        String fresh = "失败记录单列时需要统计哪些指标尚未确定。另外，还需确认退款订单是否纳入成功率分母。";
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of(new PlanAnswer("failed", question, "暂不确定"))))
                .merge(List.of(repeated, fresh), List.of(), List.of());
        assertThat(result.messages()).hasSize(2);
        assertThat(String.join("\n", result.executionPrerequisites())).contains("latency_ms=8000", "按 cached 分组", fresh);
    }
}
