package com.promptoptimizer.common.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 HTTP 完成日志使用路由模板与状态元数据，不输出请求 URI 或查询参数。 */
class RequestIdFilterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void enableTestLogger() {
        // 明确测试INFO事件，再恢复原级别，避免全仓库Spring测试顺序改变断言结果。
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        MDC.clear();
    }

    @Test
    void shouldLogRouteTemplateAndRestoreMdcAfterRequest() throws Exception {
        appender.start();
        logger.addAppender(appender);
        MDC.put("requestId", "outer-request");
        MDC.put("workflowId", "outer-workflow");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/context/planning");
        request.setQueryString("search=private-query");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                    "/api/v1/context/planning");
            ((MockHttpServletResponse) servletResponse).setStatus(500);
            MDC.put("workflowId", "request-workflow");
        };

        new RequestIdFilter().doFilter(request, response, chain);

        String message = appender.list.getFirst().getFormattedMessage();
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("trace-123");
        assertThat(message)
                .contains("event=http.request.completed")
                .contains("requestId=trace-123")
                .contains("route=/api/v1/context/planning")
                .contains("status=500")
                .doesNotContain("private-query", "/api/v1/context/planning?", "outer-request");
        assertThat(MDC.get("requestId")).isEqualTo("outer-request");
        assertThat(MDC.get("workflowId")).isEqualTo("outer-workflow");
    }
}
