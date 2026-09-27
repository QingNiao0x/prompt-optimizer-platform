package com.promptoptimizer.history.service.impl;

import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import com.promptoptimizer.history.entity.OptimizationSessionEntity;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.history.mapper.OptimizationSessionMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 在一个事务内复用或创建工作区会话，并插入对应的脱敏优化记录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
@ConditionalOnProperty(prefix = "app.history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OptimizationHistoryPersistenceService {

    private static final String SESSION_STATUS_ACTIVE = "ACTIVE";
    private static final String DEFAULT_SESSION_TITLE = "默认优化会话";

    private final OptimizationRecordMapper recordMapper;
    private final OptimizationSessionMapper sessionMapper;

    public OptimizationHistoryPersistenceService(
            OptimizationRecordMapper recordMapper,
            OptimizationSessionMapper sessionMapper
    ) {
        this.recordMapper = recordMapper;
        this.sessionMapper = sessionMapper;
    }

    /** 让默认会话与优化记录共同提交或回滚，避免留下无记录会话。 */
    @Transactional
    public UUID persist(OptimizationRecordEntity record, ActorIdentity actor) {
        OptimizationSessionEntity session = sessionMapper.selectFirstActiveByScope(
                actor.tenantId(), actor.workspaceId(), SESSION_STATUS_ACTIVE);
        if (session == null) {
            session = createDefaultSession(actor);
            if (sessionMapper.insertSession(session) != 1) {
                throw new IllegalStateException("优化会话写入失败");
            }
        }
        record.setSessionId(session.getId());
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (recordMapper.insertRecord(record) != 1) {
            throw new IllegalStateException("优化记录写入失败");
        }
        return record.getId();
    }

    private OptimizationSessionEntity createDefaultSession(ActorIdentity actor) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OptimizationSessionEntity session = new OptimizationSessionEntity();
        session.setId(UUID.randomUUID());
        session.setTenantId(actor.tenantId());
        session.setWorkspaceId(actor.workspaceId());
        session.setCreatedBy(actor.userId());
        session.setTitle(DEFAULT_SESSION_TITLE);
        session.setStatus(SESSION_STATUS_ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
    }
}
