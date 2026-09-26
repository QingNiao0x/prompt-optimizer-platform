package com.promptoptimizer.common.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.promptoptimizer.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void reportsWrappedAuthenticationInfrastructureFailureAsServerErrorWithSafeCauseDetails() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "auth-service-failure-test");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/auth/login");
            java.sql.SQLException sqlException = new java.sql.SQLException(
                    "password=private-database-message", "42P01", 0);
            sqlException.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                    "com.promptoptimizer.identity.mapper.UserIdentityMapper",
                    "selectByLoginKey", "UserIdentityMapper.xml", 28)});
            InternalAuthenticationServiceException failure = new InternalAuthenticationServiceException(
                    "private-authentication-wrapper-message", sqlException);
            failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                    "com.promptoptimizer.identity.security.DatabaseUserDetailsService",
                    "loadUserByUsername", "DatabaseUserDetailsService.java", 60)});

            var response = new GlobalExceptionHandler().handleAuthenticationFailure(failure, request);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().error().code()).isEqualTo("AUTHENTICATION_SERVICE_UNAVAILABLE");
            assertThat(response.getBody().error().retryable()).isTrue();
            assertThat(response.getBody().error().message()).isEqualTo("登录服务暂时不可用，请稍后重试。");
            assertThat(appender.list).hasSize(1);
            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .contains("event=auth.authentication_service_failure", "auth-service-failure-test",
                            "cause[0] org.springframework.security.authentication.InternalAuthenticationServiceException",
                            "cause[1] java.sql.SQLException sqlState=42P01 vendorCode=0",
                            "DatabaseUserDetailsService.loadUserByUsername(60)",
                            "UserIdentityMapper.selectByLoginKey(28)")
                    .contains("causes:\n")
                    .doesNotContain("private-database-message", "private-authentication-wrapper-message");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

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
                    "com.promptoptimizer.context.service.DefaultContextAnalyzer", "analyze", "private-path.java", 150)});

            var response = new GlobalExceptionHandler().handleUnexpectedException(
                    new IllegalStateException("private-source-text", cause), request);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().error().message()).isEqualTo("服务暂时不可用，请稍后重试。");
            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getFormattedMessage()).contains("context-failure-test", "/api/v1/context/planning",
                    "cause[0] java.lang.IllegalStateException", "cause[1] java.lang.IllegalArgumentException",
                    "DefaultContextAnalyzer.analyze(150)")
                    .contains("原因:\n")
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
            assertThat(event.getFormattedMessage())
                    .contains("cause[0] java.lang.IllegalStateException", "cause[1] java.lang.IllegalStateException")
                    .contains("route=<unmapped>")
                    .doesNotContain("private-first", "private-second", "private-route-value");
            assertThat(event.getFormattedMessage().split("cause\\[")).hasSize(3);
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldLogSqlDiagnosticsOnSeparateLinesWithoutSqlExceptionMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/analytics");
            request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "sql-failure-test");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                    "/api/v1/admin/analytics/dashboard");
            java.sql.SQLException sqlException = new java.sql.SQLException(
                    "password=private-db-message", "42601", 7);

            new GlobalExceptionHandler().handleUnexpectedException(
                    new IllegalStateException("outer-private-message", sqlException), request);

            assertThat(appender.list).hasSize(1);
            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .contains("sql-failure-test", "cause[1] java.sql.SQLException sqlState=42601 vendorCode=7")
                    .contains("原因:\n")
                    .doesNotContain("private-db-message", "outer-private-message");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void logsJvmHelpfulNullPointerMessageAndApplicationLocationWithoutRequestData() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/private-path");
            request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "npe-diagnostic-test");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/context/analyze");
            NullPointerException failure = new NullPointerException(
                    "Cannot invoke \"org.example.User.getUserName()\" because \"item\" is null");
            failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                    "com.promptoptimizer.context.service.ContextService", "analyze", "ContextService.java", 42)});

            new GlobalExceptionHandler().handleUnexpectedException(failure, request);

            assertThat(appender.list).hasSize(1);
            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .contains("请求处理失败", "npe-diagnostic-test",
                            "detail: Cannot invoke \"org.example.User.getUserName()\" because \"item\" is null",
                            "ContextService.analyze(42)")
                    .doesNotContain("/private-path");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void omitsCustomNullPointerMessagesThatCouldContainSensitiveValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/private-path");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/context/analyze");

            new GlobalExceptionHandler().handleUnexpectedException(
                    new NullPointerException("password=private-secret-value"), request);

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("cause[0] java.lang.NullPointerException")
                    .doesNotContain("private-secret-value", "password=");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
