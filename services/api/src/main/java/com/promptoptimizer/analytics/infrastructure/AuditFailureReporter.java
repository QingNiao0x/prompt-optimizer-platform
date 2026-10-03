package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按异常类型、SQLState 和约束汇总重复审计错误；首条保留安全诊断，每分钟报告省略数量。
 * 不影响投递结果或失败指标，不缓存原始异常、审计详情和用户标识。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class AuditFailureReporter {
    private static final long REPORT_INTERVAL_MILLIS = 60_000;
    private static final int MAX_FAILURE_GROUPS = 32;
    private final Logger logger;
    private final Clock clock;
    private final Map<FailureKey, Window> windows = new LinkedHashMap<>();

    /** 注入时钟使汇总窗口的边界可确定性验证，不通过修改日志级别隐藏错误。 */
    AuditFailureReporter(Logger logger, Clock clock) {
        this.logger = logger;
        this.clock = clock;
    }

    /** 首次输出安全异常链；同窗口的重复错误计入汇总，失败指标仍由投递器逐条累加。 */
    synchronized void record(PendingAuditEvent event, RuntimeException failure,
                             AuditFailureDiagnostics diagnostics, long pendingEvents) {
        flushDue(pendingEvents);
        FailureKey key = new FailureKey(failure.getClass().getSimpleName(), diagnostics.sqlState(), diagnostics.constraint());
        Window window = windows.get(key);
        if (window != null) {
            window.suppressed++;
            return;
        }
        // 约束名称虽经过校验，种类仍可能很多；限制缓存并在淘汰前报告尚未输出的次数。
        if (windows.size() >= MAX_FAILURE_GROUPS) {
            var oldest = windows.entrySet().iterator().next();
            summary(oldest.getKey(), oldest.getValue(), pendingEvents);
            windows.remove(oldest.getKey());
        }
        windows.put(key, new Window(clock.millis() + REPORT_INTERVAL_MILLIS));
        Object requestId = event.details().get("requestId");
        String safeRequestId = requestId instanceof String value && value.matches("[A-Za-z0-9_.:-]{1,128}")
                ? value : "unavailable";
        logger.error("event=analytics.audit_write_failure requestId={} eventType={} reason={} sqlState={} constraint={} pendingEvents={} eventId={}{}diagnostic: {}{}{}",
                safeRequestId, event.eventType(), key.reason(), key.sqlState(), key.constraint(), pendingEvents,
                event.id(), System.lineSeparator(), diagnostics.explanation(), System.lineSeparator(),
                AuditFailureDiagnostics.safeTrace(failure));
    }

    /** 定时投递即使处于退避，也输出到期汇总；汇总后开启新窗口以保留后续恢复故障的首条诊断。 */
    synchronized void flushDue(long pendingEvents) {
        long now = clock.millis();
        var iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().untilMillis <= now) {
                summary(entry.getKey(), entry.getValue(), pendingEvents);
                iterator.remove();
            }
        }
    }

    /** 正常恢复或实例关闭时补齐剩余汇总，不能因窗口尚未到期而遗失失败次数。 */
    synchronized void flushAll(long pendingEvents) {
        windows.forEach((key, window) -> summary(key, window, pendingEvents));
        windows.clear();
    }

    /** 汇总只带安全错误分类和计数，不保存或重新打印某个用户的事件。 */
    private void summary(FailureKey key, Window window, long pendingEvents) {
        if (window.suppressed > 0) {
            logger.error("event=analytics.audit_write_failure_summary reason={} sqlState={} constraint={} suppressedFailures={} pendingEvents={}",
                    key.reason(), key.sqlState(), key.constraint(), window.suppressed, pendingEvents);
        }
    }

    private record FailureKey(String reason, String sqlState, String constraint) { }

    private static final class Window {
        private final long untilMillis;
        private long suppressed;

        private Window(long untilMillis) {
            this.untilMillis = untilMillis;
        }
    }
}
