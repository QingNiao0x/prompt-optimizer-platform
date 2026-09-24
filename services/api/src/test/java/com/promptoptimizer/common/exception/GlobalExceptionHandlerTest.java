package com.promptoptimizer.common.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.promptoptimizer.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void shouldLocateUnexpectedContextFailuresWithoutLoggingRequestOrExceptionValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/private-customer-path");
            request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "context-failure-test");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/context/planning");
            request.setQueryString("password=example-query-secret");
            request.setContent("private-source-text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            IllegalArgumentException cause = new IllegalArgumentException("password=example-exception-secret");
            cause.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                    "com.promptoptimizer.context.application.DefaultContextAnalyzer", "analyze", "private-path.java", 150)});

            var response = new GlobalExceptionHandler().handleUnexpectedException(
                    new IllegalStateException("private-source-text", cause), request);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().error().message()).isEqualTo("服务暂时不可用，请稍后重试。");
            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getFormattedMessage()).contains("context-failure-test", "/api/v1/context/planning",
                    "java.lang.IllegalArgumentException", "DefaultContextAnalyzer.analyze:150")
                    .doesNotContain("example-query-secret", "example-exception-secret", "private-source-text",
                            "private-customer-path", "private-path.java");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldBoundDiagnosticsForCyclicExceptionCauses() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        IllegalStateException first = new IllegalStateException("private-first");
        IllegalStateException second = new IllegalStateException("private-second", first);
        first.initCause(second);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, new Object() {
            @Override
            public String toString() {
                return "private-route-value";
            }
        });
        try {
            var response = new GlobalExceptionHandler().handleUnexpectedException(first, request);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getFormattedMessage()).contains("java.lang.IllegalStateException")
                    .contains("route=<unmapped>")
                    .doesNotContain("private-first", "private-second", "private-route-value");
            assertThat(event.getFormattedMessage().split(" <- ")).hasSize(2);
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
