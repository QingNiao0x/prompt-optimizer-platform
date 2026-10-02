package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.service.AnalyticsDeliveryUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 先可靠接收，再在独立线程重放：数据库故障不延迟或污染主业务，磁盘故障必须明确告警。
 * 每实例应配置持久 journal 卷；这里只保留待投递索引，原始事件与 ack 永久保留且没有自动清理。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class AuditEventDelivery {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditEventDelivery.class);
    private final AuditEventJournal journal;
    private final AuditEventDatabaseWriter writer;
    private final int batchSize;
    private final long initialRetryMillis;
    private final long maximumRetryMillis;
    private final long pendingAlertThreshold;
    private final long oldestPendingAlertSeconds;
    private final Map<UUID, PendingAuditEvent> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Retry> retries = new ConcurrentHashMap<>();
    private final AtomicLong databaseFailures = new AtomicLong();
    private final AtomicLong journalFailures = new AtomicLong();
    private final AtomicLong recoveredEvents = new AtomicLong();
    private final AtomicBoolean immediateScheduled = new AtomicBoolean();
    private volatile long corruptFiles;
    private volatile boolean journalAvailable = true;
    private volatile boolean databaseAvailable = true;
    private volatile boolean initialized;
    private volatile Instant lastFailureAt;
    private volatile Instant lastDeliveredAt;
    private volatile long databaseNotBeforeMillis;
    private volatile long databaseRetryMillis;
    private ScheduledExecutorService worker;
    private final Counter databaseFailureMetric;
    private final Counter journalFailureMetric;

    /** 校验批量和退避边界；Micrometer 只记无用户标签的积压、失败与损坏数量。 */
    public AuditEventDelivery(AuditEventJournal journal, AuditEventDatabaseWriter writer,
                              @Value("${app.analytics.delivery.batch-size:100}") int batchSize,
                              @Value("${app.analytics.delivery.retry-initial-ms:1000}") long initialRetryMillis,
                              @Value("${app.analytics.delivery.retry-max-ms:30000}") long maximumRetryMillis,
                              @Value("${app.analytics.delivery.pending-alert-threshold:1000}") long pendingAlertThreshold,
                              @Value("${app.analytics.delivery.oldest-pending-alert-seconds:300}") long oldestPendingAlertSeconds,
                              ObjectProvider<MeterRegistry> registryProvider) {
        if (batchSize < 1 || batchSize > 1000 || initialRetryMillis < 100
                || maximumRetryMillis < initialRetryMillis || maximumRetryMillis > 300000
                || pendingAlertThreshold < 1 || oldestPendingAlertSeconds < 1) {
            throw new IllegalArgumentException("Invalid analytics delivery retry configuration");
        }
        this.journal = journal;
        this.writer = writer;
        this.batchSize = batchSize;
        this.initialRetryMillis = initialRetryMillis;
        this.maximumRetryMillis = maximumRetryMillis;
        this.pendingAlertThreshold = pendingAlertThreshold;
        this.oldestPendingAlertSeconds = oldestPendingAlertSeconds;
        MeterRegistry registry = registryProvider.getIfAvailable();
        databaseFailureMetric = registry == null ? null : registry.counter("analytics.delivery.database.failures");
        journalFailureMetric = registry == null ? null : registry.counter("analytics.delivery.journal.failures");
        if (registry != null) {
            Gauge.builder("analytics.delivery.pending.events", pending, Map::size).register(registry);
            Gauge.builder("analytics.delivery.oldest.pending.seconds", this, delivery -> delivery.oldestPendingAgeSeconds()).register(registry);
            Gauge.builder("analytics.delivery.corrupt.files", this, delivery -> delivery.corruptFiles).register(registry);
        }
    }

    /** 启动时恢复未确认事件；无城市库/数据库不妨碍 journal 接收，目录故障用明确 ERROR 表示。 */
    @PostConstruct
    public synchronized void start() {
        recoverJournal();
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "analytics-audit-delivery");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::replayPending, initialRetryMillis, initialRetryMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 成功返回表示文件 journal 或独立数据库事务已持久接收，不表示所有统计查询已经可见。
     * 主业务不因遥测失败而失败；浏览器遥测必须在 strict=true 时收到可重试 503。
     */
    public void accept(PendingAuditEvent event, boolean strict) {
        try {
            PendingAuditEvent stored = journal.append(event);
            journalAvailable = true;
            if (!journal.acknowledged(stored.id())) pending.putIfAbsent(stored.id(), stored);
            requestImmediateReplay();
        } catch (IOException | RuntimeException failure) {
            recordJournalFailure(failure, "accept");
            // 磁盘单独故障时仍尝试独立数据库提交；数据库原始明细同样永久保留，不能无谓丢弃操作。
            try {
                writer.write(event);
                databaseAvailable = true;
                databaseRetryMillis = 0;
                databaseNotBeforeMillis = 0;
                recoveredEvents.incrementAndGet();
                lastDeliveredAt = Instant.now();
                LOGGER.warn("event=analytics.audit_database_fallback eventType={}", event.eventType());
            } catch (RuntimeException databaseFailure) {
                recordDatabaseFailure(event, databaseFailure);
                LOGGER.error("event=analytics.audit_unrecoverable_failure eventType={} reason=journal_and_database_unavailable", event.eventType());
                if (strict) throw new AnalyticsDeliveryUnavailableException(databaseFailure);
            }
        }
    }

    /**
     * 有界批量重放；每条事件保留独立退避，坏约束记录不能阻断其他账号的事件。
     * 测试可直接调用进行确定性故障演练，生产由内部唯一工作线程调用。
     */
    public synchronized void replayPending() {
        if (!initialized) recoverJournal();
        long now = System.currentTimeMillis();
        if (databaseNotBeforeMillis > now) return;
        var batch = new ArrayList<>(pending.values());
        batch.sort(Comparator.comparing(PendingAuditEvent::occurredAt));
        int attempted = 0;
        for (PendingAuditEvent event : batch) {
            Retry retry = retries.get(event.id());
            if (retry != null && retry.notBeforeMillis() > now) continue;
            if (++attempted > batchSize) break;
            try {
                // writer 的 REQUIRES_NEW 代理返回后事务已经提交。不能在提交前确认，否则退出会永久漏计。
                writer.write(event);
                // 数据库提交成功与文件确认是两个独立事实；确认失败不能继续把已恢复的数据库标为不可用。
                databaseAvailable = true;
                databaseRetryMillis = 0;
                databaseNotBeforeMillis = 0;
                journal.acknowledge(event.id());
                pending.remove(event.id(), event);
                retries.remove(event.id());
                recoveredEvents.incrementAndGet();
                journalAvailable = true;
                lastDeliveredAt = Instant.now();
            } catch (IOException failure) {
                recordJournalFailure(failure, "acknowledge");
                defer(event.id(), retry, now);
            } catch (RuntimeException failure) {
                recordDatabaseFailure(event, failure);
                defer(event.id(), retry, now);
                // 连接故障影响整库，不能为每条积压都等待连接超时；约束错误则继续处理其他事件。
                if (failure instanceof org.springframework.dao.DataAccessResourceFailureException
                        || AuditFailureDiagnostics.from(failure).sqlState().startsWith("08")) {
                    databaseRetryMillis = databaseRetryMillis == 0 ? initialRetryMillis : Math.min(maximumRetryMillis, databaseRetryMillis * 2);
                    databaseNotBeforeMillis = System.currentTimeMillis() + databaseRetryMillis;
                    break;
                }
            }
        }
        if (attempted > 0 && pending.isEmpty() && databaseAvailable && journalAvailable) {
            LOGGER.info("event=analytics.audit_delivery_recovered pendingEvents=0 deliveredEvents={}", recoveredEvents.get());
        }
    }

    /** 管理员查询及告警读取无个人信息的状态快照，失败计数是进程累计值，重启后不伪造历史计数。 */
    public DeliveryStatus status() {
        Instant oldest = pending.values().stream().map(event -> event.occurredAt().toInstant()).min(Comparator.naturalOrder()).orElse(null);
        long oldestAge = oldestPendingAgeSeconds();
        boolean backlogAlert = pending.size() >= pendingAlertThreshold || oldestAge >= oldestPendingAlertSeconds;
        boolean healthy = journalAvailable && databaseAvailable && corruptFiles == 0 && retries.isEmpty() && !backlogAlert;
        // 磁盘单独故障仍可通过数据库可靠接收；只有两条通道都不可用才表示接收不可用。
        String state = !journalAvailable && !databaseAvailable ? "UNAVAILABLE" : healthy ? "HEALTHY" : "DEGRADED";
        return new DeliveryStatus(state, "INSTANCE", healthy,
                initialized, journalAvailable, databaseAvailable, pending.size(), oldest, oldestPendingAgeSeconds(),
                databaseFailures.get(), journalFailures.get(), corruptFiles, recoveredEvents.get(), lastFailureAt, lastDeliveredAt,
                backlogAlert, pendingAlertThreshold, oldestPendingAlertSeconds);
    }

    /** 有界停止工作线程；未完成投递仍保留在文件 journal，下次启动继续重放。 */
    @PreDestroy
    public synchronized void close() {
        if (worker != null) worker.shutdownNow();
    }

    /** 恢复只加载未确认事实；损坏文件保留且触发告警，不把失败事实计为成功。 */
    private void recoverJournal() {
        try {
            AuditEventJournal.Recovery recovery = journal.recover();
            recovery.events().forEach(event -> pending.putIfAbsent(event.id(), event));
            corruptFiles = recovery.corruptFiles();
            initialized = true;
            journalAvailable = true;
            if (corruptFiles > 0) LOGGER.error("event=analytics.audit_journal_corrupt corruptFiles={} pendingEvents={}", corruptFiles, pending.size());
            else if (!pending.isEmpty()) LOGGER.warn("event=analytics.audit_journal_recovered pendingEvents={}", pending.size());
        } catch (IOException | RuntimeException failure) {
            recordJournalFailure(failure, "recover");
        }
    }

    /** 合并高频请求的立即重放任务，避免每次访问向后台执行器无限追加重复任务。 */
    private void requestImmediateReplay() {
        ScheduledExecutorService executor = worker;
        if (executor != null && !executor.isShutdown() && immediateScheduled.compareAndSet(false, true)) {
            executor.execute(() -> {
                try { replayPending(); }
                finally { immediateScheduled.set(false); }
            });
        }
    }

    /** 指数退避在最大间隔封顶，不丢弃达到重试次数上限的原始事件。 */
    private void defer(UUID eventId, Retry previous, long now) {
        long interval = previous == null ? initialRetryMillis : Math.min(maximumRetryMillis, previous.intervalMillis() * 2);
        retries.put(eventId, new Retry(interval, now + interval));
    }

    /** 日志只输出阶段和异常类型；文件路径、IP、JSON、SQL 和底层异常消息都不进入日志。 */
    private void recordJournalFailure(Throwable failure, String stage) {
        journalAvailable = false;
        journalFailures.incrementAndGet();
        if (journalFailureMetric != null) journalFailureMetric.increment();
        lastFailureAt = Instant.now();
        LOGGER.error("event=analytics.audit_journal_failure stage={} reason={} pendingEvents={}", stage,
                failure.getClass().getSimpleName(), pending.size());
    }

    /** 保留原审计失败事件代码与 SQLState/约束诊断，使已有排查流程仍可关联 requestId。 */
    private void recordDatabaseFailure(PendingAuditEvent event, RuntimeException failure) {
        databaseAvailable = false;
        databaseFailures.incrementAndGet();
        if (databaseFailureMetric != null) databaseFailureMetric.increment();
        lastFailureAt = Instant.now();
        AuditFailureDiagnostics diagnostics = AuditFailureDiagnostics.from(failure);
        LOGGER.error("event=analytics.audit_write_failure requestId={} eventType={} reason={} sqlState={} constraint={} pendingEvents={}",
                event.details().get("requestId"), event.eventType(), failure.getClass().getSimpleName(),
                diagnostics.sqlState(), diagnostics.constraint(), pending.size());
    }

    /** 积压年龄依据最老事件的真实发生时刻，不因进程重启或重试而重置。 */
    private long oldestPendingAgeSeconds() {
        return pending.values().stream().mapToLong(event -> Math.max(0, Instant.now().getEpochSecond() - event.occurredAt().toEpochSecond())).max().orElse(0);
    }

    private record Retry(long intervalMillis, long notBeforeMillis) { }

    /** 管理员投递状态；不包含用户标识、所在地、文件路径或认证信息。 */
    public record DeliveryStatus(String status, String scope, boolean healthy, boolean initialized, boolean journalAvailable, boolean databaseAvailable,
                                 long pendingEvents, Instant oldestPendingAt, long oldestPendingAgeSeconds,
                                 long databaseFailures, long journalFailures, long corruptFiles, long deliveredEvents,
                                 Instant lastFailureAt, Instant lastDeliveredAt, boolean backlogAlert,
                                 long pendingAlertThreshold, long oldestPendingAlertSeconds) { }
}
