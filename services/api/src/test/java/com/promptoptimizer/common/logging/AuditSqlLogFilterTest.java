package com.promptoptimizer.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证降噪仅作用于审计工作线程的 P6Spy 明细，不掩盖错误或改变其他接口日志。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AuditSqlLogFilterTest {
    private final AuditSqlLogFilter filter = new AuditSqlLogFilter();

    @Test
    void filtersAuditSqlWithoutAccessingSensitiveMessage() {
        ILoggingEvent event = event("p6spy", "analytics-audit-delivery", Level.INFO);
        assertThat(filter.decide(event)).isEqualTo(FilterReply.DENY);
        verify(event, never()).getFormattedMessage();
        verify(event, never()).getArgumentArray();
    }

    @Test
    void preservesOtherThreadsAuditErrorsAndP6SpyWarnings() {
        assertThat(filter.decide(event("p6spy", "http-nio-9000-exec-1", Level.INFO))).isEqualTo(FilterReply.NEUTRAL);
        assertThat(filter.decide(event("com.promptoptimizer.analytics.infrastructure.AuditEventDelivery",
                "analytics-audit-delivery", Level.ERROR))).isEqualTo(FilterReply.NEUTRAL);
        for (Level level : new Level[]{Level.WARN, Level.ERROR}) {
            assertThat(filter.decide(event("p6spy", "analytics-audit-delivery", level))).isEqualTo(FilterReply.NEUTRAL);
        }
        assertThat(filter.decide(null)).isEqualTo(FilterReply.NEUTRAL);
    }

    /** 只设置分类元数据，过滤器不得依赖 SQL 文本。 */
    private ILoggingEvent event(String logger, String thread, Level level) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLoggerName()).thenReturn(logger);
        when(event.getThreadName()).thenReturn(thread);
        when(event.getLevel()).thenReturn(level);
        return event;
    }
}
