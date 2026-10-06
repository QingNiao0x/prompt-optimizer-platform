package com.promptoptimizer.analytics.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.service.AnalyticsDeliveryUnavailableException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故障演练覆盖数据库中断、重启、提交后超时、磁盘与确认故障及损坏文件保留。
 * 本类替换数据库 writer，真实 PostgreSQL 幂等和事务链由独立本地验收覆盖。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AuditEventDeliveryTest {
    @TempDir Path directory;

    @Test
    void concurrentPlanCompletionOutageAndAckFailureRecoverOnlyOneFact() throws Exception {
        var original = event();
        PendingAuditEvent completion = new PendingAuditEvent(original.id(), original.tenantId(), original.actorUserId(),
                AnalyticsEventType.PLAN_COMPLETED, original.details(), original.occurredAt());
        AuditEventDatabaseWriter unavailable = mock(AuditEventDatabaseWriter.class);
        doThrow(new DataAccessResourceFailureException("synthetic outage")).when(unavailable).write(any());
        AuditEventDelivery first = delivery(journal(), unavailable);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int attempt = 0; attempt < 32; attempt++) tasks.add(() -> { first.accept(completion, false); return null; });
            for (var result : executor.invokeAll(tasks)) result.get();
        }
        first.replayPending();
        assertThat(first.status().pendingEvents()).isEqualTo(1);
        Set<UUID> committed = new HashSet<>();
        AuditEventDatabaseWriter restored = mock(AuditEventDatabaseWriter.class);
        doAnswer(call -> committed.add(call.<PendingAuditEvent>getArgument(0).id())).when(restored).write(any());
        AuditEventJournal faultyAck = spy(journal());
        doThrow(new IOException("synthetic ack failure")).when(faultyAck).acknowledge(any());
        AuditEventDelivery second = delivery(faultyAck, restored);
        second.replayPending();
        assertThat(second.status().pendingEvents()).isEqualTo(1);
        var restarted = delivery(journal(), restored);
        restarted.replayPending();
        restarted.accept(completion, false);
        restarted.replayPending();
        assertThat(committed).containsExactly(completion.id());
        verify(restored, times(2)).write(completion);
        assertThat(restarted.status().pendingEvents()).isZero();
        assertThat(journal().acknowledged(completion.id())).isTrue();
        assertThat(Files.exists(directory.resolve(completion.id() + ".json"))).isTrue();
    }

    @Test
    void databaseOutageReturnsAfterDurableReceiptAndRestartReplaysOriginalFact() throws Exception {
        AuditEventJournal journal = journal();
        AuditEventDatabaseWriter unavailable = mock(AuditEventDatabaseWriter.class);
        doThrow(new DataAccessResourceFailureException("private connection details")).when(unavailable).write(any());
        PendingAuditEvent event = event();
        AuditEventDelivery first = delivery(journal, unavailable);

        assertThatCode(() -> first.accept(event, true)).doesNotThrowAnyException();
        verify(unavailable, times(0)).write(any());
        first.replayPending();
        assertThat(first.status().pendingEvents()).isEqualTo(1);
        assertThat(first.status().status()).isEqualTo("DEGRADED");
        assertThat(journal.acknowledged(event.id())).isFalse();

        AuditEventDatabaseWriter restored = mock(AuditEventDatabaseWriter.class);
        AuditEventDelivery restarted = delivery(journal(), restored);
        restarted.replayPending();
        verify(restored).write(event);
        assertThat(restarted.status().pendingEvents()).isZero();
        assertThat(restarted.status().healthy()).isTrue();
        assertThat(journal.acknowledged(event.id())).isTrue();
        assertThat(Files.exists(directory.resolve(event.id() + ".json"))).isTrue();
    }

    @Test
    void timeoutAfterDatabaseCommitIsReplayedWithSameIdAndNeverDoubleCounts() {
        Set<UUID> committed = new HashSet<>();
        AuditEventDatabaseWriter interrupted = mock(AuditEventDatabaseWriter.class);
        doAnswer(invocation -> {
            committed.add(invocation.<PendingAuditEvent>getArgument(0).id());
            throw new DataAccessResourceFailureException("response lost after commit");
        }).when(interrupted).write(any());
        PendingAuditEvent event = event();
        AuditEventDelivery first = delivery(journal(), interrupted);
        first.accept(event, true);
        first.replayPending();

        AuditEventDatabaseWriter restored = mock(AuditEventDatabaseWriter.class);
        doAnswer(invocation -> committed.add(invocation.<PendingAuditEvent>getArgument(0).id())).when(restored).write(any());
        delivery(journal(), restored).replayPending();
        assertThat(committed).containsExactly(event.id());
        verify(restored).write(event);
    }

    @Test
    void acknowledgmentFailureKeepsPendingUntilRestartAndRetainsRawData() throws Exception {
        AuditEventJournal faultyJournal = spy(journal());
        doThrow(new IOException("private journal path")).when(faultyJournal).acknowledge(any());
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        PendingAuditEvent event = event();
        AuditEventDelivery first = delivery(faultyJournal, writer);
        first.accept(event, true);
        first.replayPending();
        assertThat(first.status().pendingEvents()).isEqualTo(1);
        assertThat(first.status().journalFailures()).isEqualTo(1);

        AuditEventDelivery restarted = delivery(journal(), writer);
        restarted.replayPending();
        verify(writer, times(2)).write(event);
        assertThat(restarted.status().pendingEvents()).isZero();
        assertThat(journal().acknowledged(event.id())).isTrue();
        assertThat(Files.readString(directory.resolve(event.id() + ".json"))).contains(event.id().toString());
    }

    @Test
    void diskOnlyFailureFallsBackToDatabaseAndDualFailureIsExplicit() throws Exception {
        AuditEventJournal unavailable = mock(AuditEventJournal.class);
        doThrow(new IOException("private path")).when(unavailable).append(any());
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        PendingAuditEvent event = event();
        AuditEventDelivery delivery = delivery(unavailable, writer);
        assertThatCode(() -> delivery.accept(event, true)).doesNotThrowAnyException();
        verify(writer).write(event);
        assertThat(delivery.status().journalAvailable()).isFalse();

        doThrow(new DataAccessResourceFailureException("private database address")).when(writer).write(any());
        assertThatThrownBy(() -> delivery.accept(event, true)).isInstanceOf(AnalyticsDeliveryUnavailableException.class)
                .hasMessage("统计事件暂未接收，请稍后重试。");
        assertThatCode(() -> delivery.accept(event, false)).doesNotThrowAnyException();
        assertThat(delivery.status().databaseFailures()).isEqualTo(2);
    }

    @Test
    void duplicateReceiptPreservesFirstTimestampAndMetadataEvenAfterAcknowledgment() throws Exception {
        PendingAuditEvent original = event();
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        AuditEventDelivery delivery = delivery(journal(), writer);
        delivery.accept(original, true);
        delivery.accept(new PendingAuditEvent(original.id(), original.tenantId(), original.actorUserId(),
                original.eventType(), Map.of("changed", true), original.occurredAt().plusDays(1)), true);
        delivery.replayPending();
        delivery.accept(original, true);
        delivery.replayPending();
        verify(writer, times(1)).write(original);
        assertThat(journal().recover().events()).isEmpty();
    }

    @Test
    void corruptEventAndAckAreKeptAndDoNotPreventOtherEventsFromBeingDelivered() throws Exception {
        PendingAuditEvent damagedAck = event();
        PendingAuditEvent valid = event();
        AuditEventJournal journal = journal();
        journal.append(damagedAck);
        journal.append(valid);
        Path badEvent = directory.resolve(UUID.randomUUID() + ".json");
        Files.writeString(badEvent, "{partial");
        Files.writeString(directory.resolve(damagedAck.id() + ".ack"), "invalid-ack");
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        AuditEventDelivery delivery = delivery(journal, writer);
        delivery.replayPending();
        verify(writer).write(valid);
        verify(writer).write(damagedAck);
        assertThat(delivery.status().pendingEvents()).isEqualTo(1);
        assertThat(delivery.status().corruptFiles()).isEqualTo(2);
        assertThat(delivery.status().status()).isIn("UNAVAILABLE", "DEGRADED");
        assertThat(Files.readString(badEvent)).isEqualTo("{partial");
        assertThat(Files.readString(directory.resolve(damagedAck.id() + ".ack"))).isEqualTo("invalid-ack");
    }

    @Test
    void partialFileIsNeverReportedAsAcceptedAndOldPendingTriggersBacklogAlert() throws Exception {
        Files.writeString(directory.resolve(UUID.randomUUID() + ".part"), "{partial");
        assertThat(journal().recover().events()).isEmpty();
        AuditEventDelivery delivery = delivery(journal(), mock(AuditEventDatabaseWriter.class));
        delivery.accept(event(), true);
        assertThat(delivery.status().scope()).isEqualTo("INSTANCE");
        assertThat(delivery.status().backlogAlert()).isTrue();
        assertThat(delivery.status().status()).isEqualTo("DEGRADED");
    }

    @Test
    void journalRoundTripPreservesSnapshotEqualityAndOriginalInstantAcrossOffsets() throws Exception {
        PendingAuditEvent original = event();
        assertThat(original.occurredAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(original.occurredAt().toInstant()).isEqualTo(
                OffsetDateTime.parse("2026-01-01T08:00:00+08:00").toInstant());
        journal().append(original);
        assertThat(journal().recover().events()).containsExactly(original);
    }

    @Test
    void successfulDatabaseFallbackRestoresDatabaseAvailabilityAfterDualFailure() throws Exception {
        AuditEventJournal unavailable = mock(AuditEventJournal.class);
        doThrow(new IOException("private path")).when(unavailable).append(any());
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        doThrow(new DataAccessResourceFailureException("private database")).when(writer).write(any());
        AuditEventDelivery delivery = delivery(unavailable, writer);
        assertThatThrownBy(() -> delivery.accept(event(), true)).isInstanceOf(AnalyticsDeliveryUnavailableException.class);
        assertThat(delivery.status().status()).isEqualTo("UNAVAILABLE");

        doNothing().when(writer).write(any());
        delivery.accept(event(), true);
        assertThat(delivery.status().databaseAvailable()).isTrue();
        assertThat(delivery.status().journalAvailable()).isFalse();
        assertThat(delivery.status().status()).isEqualTo("DEGRADED");
        assertThat(delivery.status().deliveredEvents()).isEqualTo(1);
        assertThat(delivery.status().lastDeliveredAt()).isNotNull();
    }

    @Test
    void recoveredDatabaseIsAvailableEvenWhenAcknowledgmentStillFails() throws Exception {
        AuditEventJournal journal = spy(journal());
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        doThrow(new DataAccessResourceFailureException("private database")).when(writer).write(any());
        AuditEventDelivery delivery = delivery(journal, writer);
        delivery.accept(event(), true);
        delivery.replayPending();
        assertThat(delivery.status().databaseAvailable()).isFalse();

        doNothing().when(writer).write(any());
        doThrow(new IOException("private acknowledgment path")).when(journal).acknowledge(any());
        // 等待本测试配置的 100ms 退避，不绕过真实的连接保护与每事件重试边界。
        Thread.sleep(150);
        delivery.replayPending();
        assertThat(delivery.status().databaseAvailable()).isTrue();
        assertThat(delivery.status().journalAvailable()).isFalse();
        assertThat(delivery.status().status()).isEqualTo("DEGRADED");
        assertThat(delivery.status().pendingEvents()).isEqualTo(1);
    }

    @Test
    void retryConfigurationRejectsUnboundedOrInvalidSettings() {
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry = mock(ObjectProvider.class);
        for (long[] values : new long[][]{{0, 100, 1000, 1000, 300}, {1001, 100, 1000, 1000, 300},
                {100, 99, 1000, 1000, 300}, {100, 100, 99, 1000, 300},
                {100, 100, 300001, 1000, 300}, {100, 100, 1000, 0, 300}, {100, 100, 1000, 1000, 0}}) {
            assertThatThrownBy(() -> new AuditEventDelivery(journal(), mock(AuditEventDatabaseWriter.class),
                    (int) values[0], values[1], values[2], values[3], values[4], registry))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void repeatedConstraintFailuresProduceOneSafeDiagnosticInsteadOfPerEventErrors() {
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        doThrow(new DataIntegrityViolationException("private row data",
                new SQLException("private database message", "23503"))).when(writer).write(any());
        AuditEventDelivery delivery = delivery(journal(), writer);
        Logger logger = (Logger) LoggerFactory.getLogger(AuditEventDelivery.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (int i = 0; i < 4; i++) delivery.accept(event(), true);
            delivery.replayPending();
            assertThat(delivery.status().pendingEvents()).isEqualTo(4);
            assertThat(delivery.status().databaseFailures()).isEqualTo(4);
            assertThat(logs.list).filteredOn(entry -> entry.getFormattedMessage()
                    .contains("event=analytics.audit_write_failure")).singleElement().satisfies(entry -> {
                        assertThat(entry.getFormattedMessage()).contains("sqlState=23503", "cause[0]",
                                System.lineSeparator()).doesNotContain("private row data", "private database message");
                        assertThat(entry.getThrowableProxy()).isNull();
                    });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void constraintFailureUsesLongerBackoffWithoutBlockingANewValidEvent() throws Exception {
        PendingAuditEvent invalid = event();
        PendingAuditEvent valid = event();
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        doThrow(new DataIntegrityViolationException("private row", new SQLException("private", "23503")))
                .when(writer).write(invalid);
        AtomicLong now = new AtomicLong();
        Clock clock = clock(now);
        AuditEventDelivery delivery = delivery(journal(), writer, clock);
        delivery.accept(invalid, true);
        delivery.replayPending();
        now.set(150);
        delivery.accept(valid, true);
        delivery.replayPending();
        verify(writer, times(1)).write(invalid);
        verify(writer).write(valid);
        assertThat(journal().acknowledged(invalid.id())).isFalse();
        assertThat(journal().acknowledged(valid.id())).isTrue();
        assertThat(delivery.status().pendingEvents()).isEqualTo(1);
        assertThat(delivery.status().healthy()).isFalse();
        now.set(299_999);
        delivery.replayPending();
        verify(writer, times(1)).write(invalid);
        doNothing().when(writer).write(invalid);
        now.set(300_000);
        delivery.replayPending();
        verify(writer, times(2)).write(invalid);
        assertThat(delivery.status().healthy()).isTrue();
        assertThat(journal().acknowledged(invalid.id())).isTrue();
        assertThat(Files.exists(directory.resolve(invalid.id() + ".json"))).isTrue();
    }

    @Test
    void dueSummaryCountsEverySuppressedFailureEvenDuringLongBackoff() {
        AtomicLong now = new AtomicLong();
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        doThrow(new DataIntegrityViolationException("private", new SQLException("private", "23503")))
                .when(writer).write(any());
        AuditEventDelivery delivery = delivery(journal(), writer, clock(now));
        Logger logger = (Logger) LoggerFactory.getLogger(AuditEventDelivery.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (int i = 0; i < 4; i++) delivery.accept(event(), true);
            delivery.replayPending();
            now.set(59_999);
            delivery.replayPending();
            assertThat(logs.list).noneMatch(entry -> entry.getFormattedMessage().contains("_summary"));
            now.set(60_000);
            delivery.replayPending();
            assertThat(logs.list).filteredOn(entry -> entry.getFormattedMessage().contains("_summary"))
                    .singleElement().satisfies(entry -> assertThat(entry.getFormattedMessage())
                            .contains("suppressedFailures=3", "pendingEvents=4"));
            verify(writer, times(4)).write(any());
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void archivedTestFixturesRemainPreservedAndAreNotReplayedOnRestart() throws Exception {
        PendingAuditEvent fixture = event();
        AuditEventJournal journal = journal();
        journal.append(fixture);
        Path archive = Files.createDirectories(directory.resolve("quarantine/test-fixtures"));
        Path original = directory.resolve(fixture.id() + ".json");
        byte[] bytes = Files.readAllBytes(original);
        Files.move(original, archive.resolve(original.getFileName()));
        AuditEventDatabaseWriter writer = mock(AuditEventDatabaseWriter.class);
        AuditEventDelivery restarted = delivery(journal(), writer);
        restarted.replayPending();
        assertThat(restarted.status().healthy()).isTrue();
        assertThat(restarted.status().pendingEvents()).isZero();
        verify(writer, times(0)).write(any());
        assertThat(Files.readAllBytes(archive.resolve(original.getFileName()))).isEqualTo(bytes);
        assertThat(journal.acknowledged(fixture.id())).isFalse();
    }

    /** 使用可推进的真实时间值，验证五分钟重试与一分钟日志汇总的准确边界。 */
    private Clock clock(AtomicLong now) {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(invocation -> now.get());
        when(clock.instant()).thenAnswer(invocation -> Instant.ofEpochMilli(now.get()));
        return clock;
    }

    /** 使用真实文件 journal；临时目录由测试运行器管理，不触及部署目录。 */
    private AuditEventJournal journal() {
        return new AuditEventJournal(directory.toString(), new ObjectMapper().findAndRegisterModules());
    }

    /** 不启动后台线程，显式重放便于稳定注入故障和模拟进程重启。 */
    @SuppressWarnings("unchecked")
    private AuditEventDelivery delivery(AuditEventJournal journal, AuditEventDatabaseWriter writer) {
        return delivery(journal, writer, Clock.systemUTC());
    }

    /** 与运行期相同的构造校验，仅替换时钟以缩短重试回归。 */
    @SuppressWarnings("unchecked")
    private AuditEventDelivery delivery(AuditEventJournal journal, AuditEventDatabaseWriter writer, Clock clock) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfAvailable()).thenReturn(null);
        return new AuditEventDelivery(journal, writer, 100, 100, 1000, 1000, 300, registry, clock);
    }

    /** 固定历史发生时刻，验证重放不以当前时间覆盖统计归属日期。 */
    private PendingAuditEvent event() {
        return new PendingAuditEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AnalyticsEventType.APP_VISIT,
                Map.of("requestId", "safe-test-request", "deviceType", "DESKTOP"),
                OffsetDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8)));
    }
}
