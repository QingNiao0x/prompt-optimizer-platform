package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.mapper.AuditEventMapper;
import com.promptoptimizer.enhancement.service.PlanQualityMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证只有首次插入且事务成功提交的事件增加运行期指标，遥测失败不改变持久化结果。 */
@ExtendWith(OutputCaptureExtension.class)
class AuditEventDatabaseWriterTest {
    @Test
    void incrementsAfterCommitOnlyAndDoesNotCountReplayOrGenericEvents() {
        AuditEventMapper mapper = mock(AuditEventMapper.class);
        PlanQualityMetrics metrics = mock(PlanQualityMetrics.class);
        var writer = new AuditEventDatabaseWriter(provider(mapper), metrics);
        var transaction = new TransactionTemplate(new TestTransactionManager(false));
        when(mapper.insert(any(), any(), any(), any(), any(), any())).thenReturn(1, 0, 1, 1);
        transaction.executeWithoutResult(status -> {
            writer.write(event(AnalyticsEventType.PLAN_COMPLETED));
            verifyNoInteractions(metrics);
        });
        verify(metrics).featureUse(AnalyticsEventType.PLAN_COMPLETED);
        transaction.executeWithoutResult(status -> writer.write(event(AnalyticsEventType.PLAN_COMPLETED)));
        transaction.executeWithoutResult(status -> writer.write(event(AnalyticsEventType.OPTIMIZATION_SUBMITTED)));
        transaction.executeWithoutResult(status -> writer.write(event(AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED)));
        verify(metrics).featureUse(AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void rollbackCommitFailureAndInsertFailureNeverIncrementCounters() {
        AuditEventMapper mapper = mock(AuditEventMapper.class);
        PlanQualityMetrics metrics = mock(PlanQualityMetrics.class);
        var writer = new AuditEventDatabaseWriter(provider(mapper), metrics);
        when(mapper.insert(any(), any(), any(), any(), any(), any())).thenReturn(1);
        new TransactionTemplate(new TestTransactionManager(false)).executeWithoutResult(status -> {
            writer.write(event(AnalyticsEventType.PLAN_COMPLETED));
            status.setRollbackOnly();
        });
        assertThatThrownBy(() -> new TransactionTemplate(new TestTransactionManager(true))
                .executeWithoutResult(status -> writer.write(event(AnalyticsEventType.PLAN_COMPLETED))))
                .isInstanceOf(TransactionSystemException.class);
        when(mapper.insert(any(), any(), any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> new TransactionTemplate(new TestTransactionManager(false))
                .executeWithoutResult(status -> writer.write(event(AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(metrics);
    }

    @Test
    void metricFailureAfterCommitIsSafelyLoggedAndDoesNotFailDelivery(CapturedOutput output) {
        AuditEventMapper mapper = mock(AuditEventMapper.class);
        PlanQualityMetrics metrics = mock(PlanQualityMetrics.class);
        when(mapper.insert(any(), any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("private provider key or prompt")).when(metrics).featureUse(any());
        var writer = new AuditEventDatabaseWriter(provider(mapper), metrics);
        assertThatCode(() -> new TransactionTemplate(new TestTransactionManager(false))
                .executeWithoutResult(status -> writer.write(event(AnalyticsEventType.PLAN_COMPLETED)))).doesNotThrowAnyException();
        assertThat(output).contains("event=analytics.usage_metric_failure", "requestId=writer-test", "eventType=PLAN_COMPLETED", "reason=IllegalStateException")
                .doesNotContain("private provider key or prompt");
    }

    /** 仅使用白名单元数据构造合成审计事实。 */
    private PendingAuditEvent event(AnalyticsEventType type) {
        return new PendingAuditEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type,
                Map.of("requestId", "writer-test"), OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** 不初始化数据源，单独验证事务同步回调的行为。 */
    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** 控制提交结果的最小事务管理器；真实 PostgreSQL 幂等写入另由数据库验收覆盖。 */
    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final boolean failCommit;
        private TestTransactionManager(boolean failCommit) { this.failCommit = failCommit; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new TransactionSystemException("test commit failure");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
