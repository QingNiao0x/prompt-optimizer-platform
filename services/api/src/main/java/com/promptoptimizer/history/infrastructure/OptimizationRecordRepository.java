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

    /**
     * 分页查询工作区内的历史记录，按创建时间倒序。
     */
    @Query("""
            select history from OptimizationRecordEntity history
            where history.tenantId = :tenantId
              and history.workspaceId = :workspaceId
              and history.deletedAt is null
              and (:keyword is null or lower(history.rawPrompt) like lower(concat('%', :keyword, '%')))
              and (:createdFrom is null or history.createdAt >= :createdFrom)
              and (:createdToExclusive is null or history.createdAt < :createdToExclusive)
            order by history.createdAt desc
            """)
    Page<OptimizationRecordEntity> findFilteredByTenantIdAndWorkspaceId(
            @Param("tenantId")
            UUID tenantId,
            @Param("workspaceId")
            UUID workspaceId,
            @Param("keyword")
            String keyword,
            @Param("createdFrom")
            OffsetDateTime createdFrom,
            @Param("createdToExclusive")
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
