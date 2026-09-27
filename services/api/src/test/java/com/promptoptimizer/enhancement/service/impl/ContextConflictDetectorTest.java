package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContextConflictDetectorTest {
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
                .isEmpty();
    }
}
