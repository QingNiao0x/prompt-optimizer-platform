package com.promptoptimizer.history.mapper;

import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 通过 XML 访问优化记录；每个查询都要求租户和工作区范围，且显式排除逻辑删除记录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface OptimizationRecordMapper {

    /**
     * 在当前租户和工作区内分页读取未删除记录的列表摘要。
     * 不返回完整优化提示词、上下文快照和权限策略。
     * 条数和偏移写在 SQL 中：这条语句交给分页插件时不会附带 LIMIT，会把命中行一次返回。
     */
    List<OptimizationRecordEntity> selectPageByScope(
            @Param("tenantId") UUID tenantId,
            @Param("workspaceId") UUID workspaceId,
            @Param("keyword") String keyword,
            @Param("createdFrom") OffsetDateTime createdFrom,
            @Param("createdToExclusive") OffsetDateTime createdToExclusive,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    /** 统计同一筛选条件下未删除的记录数，供分页总页数使用。 */
    long countByScope(
            @Param("tenantId") UUID tenantId,
            @Param("workspaceId") UUID workspaceId,
            @Param("keyword") String keyword,
            @Param("createdFrom") OffsetDateTime createdFrom,
            @Param("createdToExclusive") OffsetDateTime createdToExclusive
    );

    /**
     * 按记录、租户和工作区共同定位未删除记录。
     */
    OptimizationRecordEntity selectByIdAndScope(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("workspaceId") UUID workspaceId
    );

    /**
     * 仅在当前租户和工作区内逻辑删除尚未删除的记录。
     */
    int markDeletedByIdAndScope(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("workspaceId") UUID workspaceId,
            @Param("deletedAt") OffsetDateTime deletedAt
    );

    /**
     * 插入调用方已填充身份、作用域和创建时间的优化记录。
     */
    int insertRecord(@Param("record") OptimizationRecordEntity record);
}
