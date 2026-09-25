package com.promptoptimizer.history.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 优化历史数据访问接口，所有查询都限定租户和工作区，避免跨租户读取。
 */
public interface OptimizationRecordRepository extends JpaRepository<OptimizationRecordEntity, UUID> {

    /** 分页查询工作区内的历史记录，按创建时间倒序。 */
    Page<OptimizationRecordEntity> findByTenantIdAndWorkspaceIdOrderByCreatedAtDesc(
            UUID tenantId,
            UUID workspaceId,
            Pageable pageable
    );

    /** 按原始提示词模糊搜索，避免把空关键字作为可空 SQL 参数传给 PostgreSQL。 */
    Page<OptimizationRecordEntity> findByTenantIdAndWorkspaceIdAndRawPromptContainingIgnoreCaseOrderByCreatedAtDesc(
            UUID tenantId,
            UUID workspaceId,
            String rawPrompt,
            Pageable pageable
    );

    /** 按创建时间范围查询，结束时间使用排他上界。 */
    Page<OptimizationRecordEntity> findByTenantIdAndWorkspaceIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            UUID tenantId,
            UUID workspaceId,
            OffsetDateTime createdFrom,
            OffsetDateTime createdToExclusive,
            Pageable pageable
    );

    /** 同时按原始提示词和创建时间范围查询。 */
    Page<OptimizationRecordEntity> findByTenantIdAndWorkspaceIdAndRawPromptContainingIgnoreCaseAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            UUID tenantId,
            UUID workspaceId,
            String rawPrompt,
            OffsetDateTime createdFrom,
            OffsetDateTime createdToExclusive,
            Pageable pageable
    );

    /**
     * 按 ID、租户和工作区查询单条记录，用于权限隔离。
     */
    Optional<OptimizationRecordEntity> findByIdAndTenantIdAndWorkspaceIdAndDeletedAtIsNull(
            UUID id,
            UUID tenantId,
            UUID workspaceId
    );

    /** 在租户和工作区边界内原子标记删除时间，不物理移除历史记录。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OptimizationRecordEntity history
               set history.deletedAt = :deletedAt
             where history.id = :id
               and history.tenantId = :tenantId
               and history.workspaceId = :workspaceId
               and history.deletedAt is null
            """)
    int markDeletedByIdAndTenantIdAndWorkspaceId(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("workspaceId") UUID workspaceId,
            @Param("deletedAt") OffsetDateTime deletedAt
    );
}
