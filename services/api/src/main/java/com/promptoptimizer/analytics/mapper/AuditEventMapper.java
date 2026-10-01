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

    /**
     * 追加一次服务端确认的审计事件，JSONB 只保存调用方已筛选的元数据。
     *
     * @param id 新事件的随机主键，不复用请求或登录会话标识
     * @param tenantId 当前认证主体的租户归属
     * @param actorUserId 当前登录账号 ID，是用户统计去重的唯一标识
     * @param eventType 服务端枚举确认的关键操作类型
     * @param details 位置、设备和非认证关联号等白名单字段，不包含凭据或原始提示词
     * @param occurredAt 以带时区时刻记录的操作时间，采集端使用 UTC
     * @return 插入行数；数据库异常由采集服务按既有非阻断策略处理
     */
    int insert(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("actorUserId") UUID actorUserId,
            @Param("eventType") AnalyticsEventType eventType,
            @Param("details") Map<String, Object> details,
            @Param("occurredAt") OffsetDateTime occurredAt
    );
}
