package com.promptoptimizer.analytics.support;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 聚合回归专用 Mapper，仅在回滚事务中设置随机测试账号的创建时间。
 * UUID、租户与测试显示名称共同限定更新范围，SQL 不进入生产构建。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface AnalyticsAggregationFixtureMapper {
    /** 设置本次新建账号的创建时刻，以验证新增账号日桶与事件日桶分别计算。 */
    int setCreatedAt(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId,
                     @Param("displayName") String displayName, @Param("createdAt") OffsetDateTime createdAt);
}
