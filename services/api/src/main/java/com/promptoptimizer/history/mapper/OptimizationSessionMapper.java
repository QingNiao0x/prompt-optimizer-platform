package com.promptoptimizer.history.mapper;

import com.promptoptimizer.history.entity.OptimizationSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/**
 * 通过 XML 访问优化会话，并确保会话读写携带租户与工作区范围。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface OptimizationSessionMapper {

    /** 返回指定租户和工作区中最早创建的活动会话。 */
    OptimizationSessionEntity selectFirstActiveByScope(
            @Param("tenantId") UUID tenantId,
            @Param("workspaceId") UUID workspaceId,
            @Param("status") String status
    );

    /** 在调用方事务中创建作用域明确的会话。 */
    int insertSession(@Param("session") OptimizationSessionEntity session);
}
