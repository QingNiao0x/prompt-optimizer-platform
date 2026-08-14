package com.promptoptimizer.history.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 优化会话数据访问接口，MVP 阶段只使用工作区默认会话。
 */
public interface OptimizationSessionRepository extends JpaRepository<OptimizationSessionEntity, UUID> {

    /**
     * 查询指定工作区最早的启用会话，用于复用默认会话。
     */
    Optional<OptimizationSessionEntity> findFirstByTenantIdAndWorkspaceIdAndStatusOrderByCreatedAtAsc(
            UUID tenantId,
            UUID workspaceId,
            String status
    );
}
