package com.promptoptimizer.analytics.mapper;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 通过 XML 向追加式 audit_event 表写入服务端确认的关键操作事件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface AuditEventMapper {

    /** 使用认证主体提供租户与账户 ID，JSONB 只包含调用方已筛选的审计元数据。 */
    int insert(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("actorUserId") UUID actorUserId,
            @Param("eventType") AnalyticsEventType eventType,
            @Param("details") Map<String, Object> details,
            @Param("occurredAt") OffsetDateTime occurredAt
    );
}
