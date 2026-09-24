package com.promptoptimizer.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证模型调用日志记录受限元数据、明确用量及单行安全字段。 */
class ModelCallLoggerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ModelCallLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    void shouldLogPublicModelAndExplicitTokenUsageWithoutMultilineFields() {
        appender.start();
        logger.addAppender(appender);
        MDC.put("requestId", "request-123");
        MDC.put("workflowId", "workflow-hash");

        ModelCallLogger.completed(
                "plan.generate\napiKey=must-not-be-a-field",
                "primary-provider",
                "public-model-v2",
                "USER_SELECTED",
                false,
                2,
                1,
                45,
                new ModelCallLogger.TokenUsage(18L, 31L, 49L)
        );

        String message = appender.list.getFirst().getFormattedMessage();
        assertThat(message)
                .contains("event=model.call.completed")
                .contains("requestId=request-123")
                .contains("workflowId=workflow-hash")
                .contains("resolvedModelId=public-model-v2")
                .contains("inputTokens=18")
                .contains("outputTokens=31")
                .contains("totalTokens=49")
                .contains("operation=[REDACTED]")
                .doesNotContain("must-not-be-a-field", "\n");
    }

    @Test
    void shouldMarkUnavailableUsageInsteadOfEstimating() {
        appender.start();
        logger.addAppender(appender);

        ModelCallLogger.completed("context.embedding", "semantic-retrieval", "embedding-v1",
                "SERVER_CONFIGURED", false, 1, 3, 12, null);

        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("inputTokens=-")
                .contains("outputTokens=-")
                .contains("totalTokens=-");
    }

    @Test
    void shouldLogFailureCategoryStatusAndRetryDecision() {
        appender.start();
        logger.addAppender(appender);

        ModelCallLogger.failed("plan.generate", "primary", "public-model-v2", "USER_SELECTED",
                "UPSTREAM_UNAVAILABLE", true, 502, true, 2, 1, 73);

        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("event=model.call.failed")
                .contains("failureType=UPSTREAM_UNAVAILABLE")
                .contains("upstreamStatus=502")
                .contains("retryable=true")
                .contains("willRetry=true")
                .contains("attempt=2")
                .contains("durationMs=73");
    }
}
