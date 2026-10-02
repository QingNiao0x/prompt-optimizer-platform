package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.mapper.AuditEventMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立事务中落库审计事实，避免数据库异常污染主业务事务。
 * 必须由另一个 Spring Bean 调用，代理返回时已提交，随后才能写 journal 的 ack。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class AuditEventDatabaseWriter {
    private final AuditEventMapper mapper;

    /** 允许无数据源的本地启动；缺少 Mapper 作为可恢复投递故障，不丢弃 journal。 */
    public AuditEventDatabaseWriter(ObjectProvider<AuditEventMapper> provider) {
        mapper = provider.getIfAvailable();
    }

    /**
     * 按事件主键幂等写入；零行表示之前已经提交，不代表丢失事件。
     * @throws org.springframework.dao.DataAccessException 数据库不可用或约束错误，保留 journal 继续恢复
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void write(PendingAuditEvent event) {
        if (mapper == null) throw new DataAccessResourceFailureException("Audit mapper unavailable");
        mapper.insert(event.id(), event.tenantId(), event.actorUserId(), event.eventType(), event.details(), event.occurredAt());
    }
}
