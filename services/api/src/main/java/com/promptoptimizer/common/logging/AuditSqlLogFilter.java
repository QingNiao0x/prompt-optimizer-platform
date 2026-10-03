package com.promptoptimizer.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 在控制台过滤审计后台线程的逐条 P6Spy 调试输出；审计错误由投递器提供安全诊断与计数。
 * 保留 P6Spy 的 WARN/ERROR，其他线程的 SQL 诊断及所有业务日志不受影响。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class AuditSqlLogFilter extends Filter<ILoggingEvent> {
    /** 仅按日志来源、线程及级别过滤，不读取可能带敏感绑定值的 SQL 消息。 */
    @Override
    public FilterReply decide(ILoggingEvent event) {
        return event != null && "p6spy".equals(event.getLoggerName())
                && "analytics-audit-delivery".equals(event.getThreadName())
                && event.getLevel() != null && !event.getLevel().isGreaterOrEqual(Level.WARN)
                ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}
