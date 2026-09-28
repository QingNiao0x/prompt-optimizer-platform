package com.promptoptimizer.analytics.support;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 本地库验收专用 Mapper：只查询随机测试账户的记录，修改操作额外校验测试标记。
 * SQL 位于 test/resources/mapper/analytics，不进入生产构建；数据随测试事务回滚。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface AnalyticsAcceptanceMapper {

    /** 读取测试账户事件代码，不返回其他账户的审计信息。 */
    List<String> eventTypes(@Param("userId") UUID userId);

    /** 返回白名单审计 JSON，供测试校验来源、身份绑定及敏感信息排除。 */
    List<String> eventDetails(@Param("userId") UUID userId);

    /** 设置随机测试账户的创建时间，验证统计日期边界。 */
    int setCreatedAt(@Param("userId") UUID userId, @Param("createdAt") OffsetDateTime createdAt);

    /** 仅修改带验收标记的测试账户，验证会话缓存角色不能绕过数据库撤权。 */
    int setAccess(@Param("userId") UUID userId, @Param("role") String role, @Param("status") String status);

    /** 回滚后确认本次账户、工作区、历史和审计记录均未残留。 */
    long remainingRows(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId,
                       @Param("workspaceId") UUID workspaceId);
}
