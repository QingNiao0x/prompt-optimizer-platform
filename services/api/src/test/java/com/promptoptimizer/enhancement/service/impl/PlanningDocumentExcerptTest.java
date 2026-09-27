package com.promptoptimizer.enhancement.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningDocumentExcerptTest {
    @Test
    void shouldSelectLateBusinessRuleInsteadOfOnlyTheDocumentOpening() {
        String content = "项目背景和一般介绍。".repeat(120)
                + "订单金额超过五万元时，必须先由财务复核。";

        String excerpt = PlanningDocumentExcerpt.select(content, "按方案实现订单审批", 600);

        assertThat(excerpt).contains("超过五万元", "财务复核");
        assertThat(excerpt.length()).isLessThanOrEqualTo(600);
    }

    @Test
    void shouldExcludeProtectedPathMentionsFromThePlanningExcerpt() {
        String excerpt = PlanningDocumentExcerpt.select(
                "不要读取 .env。订单金额超过五万元时，必须先由财务复核。",
                "实现订单审批", 600);

        assertThat(excerpt).contains("财务复核").doesNotContain(".env");
    }

    @Test
    void shouldSampleTheEndOfAnUnstructuredLongLine() {
        String content = "一般说明".repeat(300)
                + "订单金额超过五万元须财务复核";

        String excerpt = PlanningDocumentExcerpt.select(content, "实现订单审批", 600);

        assertThat(excerpt).contains("超过五万元", "财务复核");
    }
}
