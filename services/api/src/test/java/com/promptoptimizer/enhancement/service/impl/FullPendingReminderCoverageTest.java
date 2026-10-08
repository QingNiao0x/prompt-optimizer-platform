package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放真实长说明与短状态重复，仅删除已经被同对象完整说明覆盖的纯状态。
 * 机构、年份、属性和新增条件参与核对，完整解释及新冲突不裁剪。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class FullPendingReminderCoverageTest {
    private static final String RAW = "甲院和乙院候诊时间比较，两院需要独立计算。"
            + "甲院观察窗口尚未确定。乙院观察窗口尚未确定。甲院异常等待阈值尚未确定。";

    @Test
    void replaysTheFirstActualCompleteReminderListWithoutFourExtraStatuses() throws Exception {
        try (var stream = getClass().getResourceAsStream("/enhancement/full-short-actual-20261008.json")) {
            var fixture = new ObjectMapper().readTree(stream);
            var findings = new ObjectMapper().convertValue(fixture.get("findings"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            var result = merge(fixture.get("rawPrompt").asText(), findings);
            assertThat(findings).hasSize(9);
            assertThat(result.executionPrerequisites()).containsExactlyElementsOf(findings.subList(0, 5));
            assertThat(result.omittedCount()).isZero();
        }
    }

    @Test
    void aCompleteExplanationCoversItsPureStateRegardlessOfOrderOrPendingWording() {
        String full = "甲院的观察窗口待确认，影响统计时间边界；新增跨院共享仍需独立审批。";
        String shortState = "甲院的观察窗口尚未确定。";
        assertThat(merge(RAW, List.of(full, shortState)).executionPrerequisites()).containsExactly(full);
        assertThat(merge(RAW, List.of(shortState, full)).executionPrerequisites()).containsExactly(full);
    }

    @Test
    void otherInstitutionYearMetricAndNewConditionAreNeverConsumed() {
        String full = "甲院的观察窗口待确认，影响统计时间边界。";
        for (String independent : List.of("乙院的观察窗口尚未确定。", "甲院2026年的观察窗口尚未确定。",
                "甲院异常等待的阈值尚未确定。", "甲院的观察窗口待确认，新增跨院共享还需审批。",
                "甲院的观察窗口待确认，若启用夜间数据需另核实采集范围。")) {
            assertThat(merge(RAW, List.of(full, independent)).executionPrerequisites()).as(independent)
                    .contains(full, independent);
        }
    }

    @Test
    void quotedOrFutureDescriptionsDoNotCoverTheCurrentUnknown() {
        String shortState = "甲院的观察窗口尚未确定。";
        for (String scoped : List.of("如果以后甲院的观察窗口待确认，再提交审批。",
                "\"甲院的观察窗口待确认，影响统计时间边界。\"", "> 甲院的观察窗口待确认。")) {
            assertThat(merge(RAW, List.of(scoped, shortState)).executionPrerequisites()).contains(shortState);
        }
    }

    @Test
    void researchDenominatorsUseTheSameCoverageAndKeepIndependentMetrics() {
        String raw = "完整性指标分母尚未确定。跨字段一致性指标分母尚未确定。";
        String full = "完整性指标的分母待确认，影响缺失率的计算范围。";
        assertThat(merge(raw, List.of(full, "完整性指标的分母尚未确定。",
                "跨字段一致性指标的分母尚未确定。")).executionPrerequisites())
                .containsExactly(full, "跨字段一致性指标的分母尚未确定。");
    }

    private static PlanAmbiguityMerger.MergeResult merge(String raw, List<String> findings) {
        return new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of()), raw)
                .merge(findings, List.of(), List.of());
    }
}
