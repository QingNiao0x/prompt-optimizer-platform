package com.promptoptimizer.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证工作流关联字段只暴露哈希且 MDC 作用域退出时恢复原值。 */
class LogCorrelationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldHashWorkflowResourceAndRestorePreviousValue() {
        String resourceId = "raw-context-id-that-must-not-appear";
        MDC.put("workflowId", "outer-workflow");

        try (LogCorrelation.Scope ignored = LogCorrelation.bindWorkflow("planning-context", resourceId)) {
            assertThat(MDC.get("workflowId")).isEqualTo(LogCorrelation.fingerprint("planning-context", resourceId));
            assertThat(MDC.get("workflowId")).doesNotContain(resourceId);
            assertThat(MDC.get("workflowId")).hasSize(20);
        }

        assertThat(MDC.get("workflowId")).isEqualTo("outer-workflow");
    }

    @Test
    void shouldSanitizeAndRestoreRequestIdForAsyncWork() {
        MDC.put("requestId", "parent-request");

        try (LogCorrelation.Scope ignored = LogCorrelation.bindRequestId("async\nrequest=id")) {
            assertThat(MDC.get("requestId")).isEqualTo("async_request_id");
        }

        assertThat(MDC.get("requestId")).isEqualTo("parent-request");
    }
}
