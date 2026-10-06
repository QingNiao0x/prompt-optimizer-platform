package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.mapper.AuditEventMapper;
import com.promptoptimizer.enhancement.service.PlanQualityMetrics;
import com.promptoptimizer.common.logging.LogFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在独立事务中落库审计事实，避免数据库异常污染主业务事务。
 * 必须由另一个 Spring Bean 调用，代理返回时已提交，随后才能写 journal 的 ack。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class AuditEventDatabaseWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditEventDatabaseWriter.class);
    private final AuditEventMapper mapper;
    private final PlanQualityMetrics metrics;

    /** 允许无数据源的本地启动；缺少 Mapper 作为可恢复投递故障，不丢弃 journal。 */
    public AuditEventDatabaseWriter(ObjectProvider<AuditEventMapper> provider) {
        this(provider, null);
    }

    /** 运行时装配有界计数器；数据库仍是永久统计的事实来源。 */
    @Autowired
    public AuditEventDatabaseWriter(ObjectProvider<AuditEventMapper> provider, PlanQualityMetrics metrics) {
        mapper = provider.getIfAvailable();
        this.metrics = metrics;
    }

    /**
     * 按事件主键幂等写入；零行表示之前已经提交，不代表丢失事件。
     * @throws org.springframework.dao.DataAccessException 数据库不可用或约束错误，保留 journal 继续恢复
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void write(PendingAuditEvent event) {
        if (mapper == null) throw new DataAccessResourceFailureException("Audit mapper unavailable");
        int inserted = mapper.insert(event.id(), event.tenantId(), event.actorUserId(), event.eventType(), event.details(), event.occurredAt());
        recordFeatureAfterCommit(event, inserted);
    }

    /** 仅首次插入并真正提交后增加运行期计数，回滚或 ON CONFLICT 重放不触发计数。 */
    private void recordFeatureAfterCommit(PendingAuditEvent event, int inserted) {
        if (inserted != 1 || metrics == null || (event.eventType() != AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED
                && event.eventType() != AnalyticsEventType.PLAN_COMPLETED)) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            LOGGER.warn("event=analytics.usage_metric_skipped reason=transaction_missing eventType={}", event.eventType());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    metrics.featureUse(event.eventType());
                } catch (RuntimeException failure) {
                    // 指标故障不能把已提交的审计写入变成业务失败，也不能阻止 journal 确认。
                    LOGGER.warn("event=analytics.usage_metric_failure requestId={} eventType={} reason={}",
                            LogFields.value(String.valueOf(event.details().get("requestId"))),
                            event.eventType(), failure.getClass().getSimpleName());
                }
            }
        });
    }
}
