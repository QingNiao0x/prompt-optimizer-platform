package com.promptoptimizer.history.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 优化历史数据访问接口，所有查询都限定租户和工作区，避免跨租户读取。
 */
public interface OptimizationRecordRepository extends JpaRepository<OptimizationRecordEntity, UUID> {

    /**
     * 分页查询工作区内的历史记录，按创建时间倒序。
     */
    Page<OptimizationRecordEntity> findByTenantIdAndWorkspaceIdOrderByCreatedAtDesc(
            UUID tenantId,
            UUID workspaceId,
            Pageable pageable
    );

    /**
     * 按 ID、租户和工作区查询单条记录，用于权限隔离。
     */
    Optional<OptimizationRecordEntity> findByIdAndTenantIdAndWorkspaceId(
            UUID id,
            UUID tenantId,
            UUID workspaceId
    );
}
