package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningEvidencePolicyTest {
    @Test
    void shouldClassifyWindowsAndNestedPathsWithoutConfusingProductionClassSuffix() {
        assertThat(PlanningEvidencePolicy.origin("backend\\src\\test\\java\\RuleTest.java", "java"))
                .isEqualTo(PlanningFactOrigin.TEST_SOURCE);
        assertThat(PlanningEvidencePolicy.origin("src/main/java/Credit.java", "java"))
                .isEqualTo(PlanningFactOrigin.PROJECT_SOURCE);
        assertThat(PlanningEvidencePolicy.origin("tests/fixtures/data.csv", "csv"))
                .isEqualTo(PlanningFactOrigin.TEST_FIXTURE);
        assertThat(PlanningEvidencePolicy.origin("apps/web/e2e/login.spec.ts", "ts"))
                .isEqualTo(PlanningFactOrigin.TEST_SOURCE);
        assertThat(PlanningEvidencePolicy.origin("apps/web/playwright-report/index.html", "html"))
                .isEqualTo(PlanningFactOrigin.GENERATED_REPORT);
        assertThat(PlanningEvidencePolicy.origin("examples/Order.java", "java"))
                .isEqualTo(PlanningFactOrigin.EXAMPLE_MATERIAL);
        assertThat(PlanningEvidencePolicy.origin("docs/development/规范.md", "md"))
                .isEqualTo(PlanningFactOrigin.PROJECT_DOCUMENT);
        assertThat(PlanningEvidencePolicy.origin("方案.docx", "docx"))
                .isEqualTo(PlanningFactOrigin.USER_MATERIAL);
        assertThat(PlanningEvidencePolicy.origin(null, null)).isEqualTo(PlanningFactOrigin.UNKNOWN);
    }

    @Test
    void shouldReserveDigestForBusinessFilesButAllowExplicitTestAndReportTasks() {
        var production = file("src/main/java/Analytics.java", "统计日志");
        var tests = file("src/test/java/AnalyticsTest.java", "统计日志测试");
        var report = file("playwright-report/index.html", "统计日志测试报告");
        assertThat(PlanningDigestSelector.select(List.of(production, tests, report), "设计统计日志模块", 30))
                .containsExactly(production);
        assertThat(PlanningDigestSelector.select(List.of(production, tests), "修复统计日志测试", 30))
                .contains(tests);
        assertThat(PlanningDigestSelector.select(List.of(report), "分析测试报告", 30)).containsExactly(report);
        assertThat(PlanningDigestSelector.select(List.of(production, tests), "检查 AnalyticsTest.java", 30))
                .contains(tests);
    }

    @Test
    void shouldPreserveSourcePunctuationAndRealMarkdownCodeBlocks() {
        String source = "void check() { int limit = 100; return; }";
        var policy = new PlanningEvidencePolicy("核对日志限制");
        assertThat(policy.contextText(new FileSnippet("src/Limit.java", "java", source, "", false)))
                .isEqualTo(source);
        String documentation = "## 当前限制\n```java\n" + source + "\n```";
        assertThat(policy.contextText(new FileSnippet("docs/日志.md", "md", documentation, "", false)))
                .contains(source);
    }

    @Test
    void shouldRespectSmallBudgetAndHandleEmptyInputs() {
        assertThat(PlanningDigestSelector.select(null, null, 0)).isEmpty();
        assertThat(PlanningDigestSelector.select(List.of(file("docs/a.md", "订单"),
                file("docs/b.md", "订单"), file("src/Order.java", "订单")), "订单", 1)).hasSize(1);
        assertThat(new PlanningEvidencePolicy("").allows(null)).isFalse();
    }

    private FileSnippet file(String path, String summary) {
        return new FileSnippet(path, "", "", summary, false);
    }
}
