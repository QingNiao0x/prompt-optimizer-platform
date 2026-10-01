package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContextConflictDetectorTest {
    @Test
    void shouldNotReintroduceTestFixtureThroughConflictWarnings() {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet("docs/统计日志规则.txt", "text", "统计口径：按上海时区", "", false),
                new FileSnippet("tests/fixtures/统计样例.txt", "text", "统计口径：按UTC时区", "", false)
        ), List.of(), List.of(), "v1");
        assertThat(new ContextConflictDetector().detect(context, List.of(), "设计统计日志模块")).isEmpty();
        assertThat(new ContextConflictDetector().detect(context, List.of(), "修复统计口径测试用例"))
                .singleElement().asString().contains("统计口径");
    }

    @Test
    void shouldExposeNewCrossFileConflictUnlessUserAlreadyResolvedTheSameField() {
        ContextSnapshot context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet("docs/现行规则.txt", "text", "审批阈值：50000", "", false),
                new FileSnippet("docs/新方案.txt", "text", "审批阈值：100000", "", false)
        ), List.of(), List.of(), "v1");
        ContextConflictDetector detector = new ContextConflictDetector();
        assertThat(detector.detect(context, List.of())).singleElement()
                .asString().contains("审批阈值", "现行规则.txt", "新方案.txt", "请确认");
        assertThat(detector.detect(context, List.of(new PlanAnswer("threshold", "审批阈值采用哪一个？", "50000"))))
                .isNotEmpty();
        // 只有服务端签发且绑定了本次两个取值的冲突题，才有资格消除旧冲突。
        String question = detector.detect(context, List.of()).getFirst();
        assertThat(detector.detect(context, List.of(new PlanAnswer("context-conflict-1", question, "50000"))))
                .isEmpty();
    }
}
