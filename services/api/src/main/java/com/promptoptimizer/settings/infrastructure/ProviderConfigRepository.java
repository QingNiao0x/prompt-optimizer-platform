package com.promptoptimizer.settings.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider 配置数据访问接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface ProviderConfigRepository extends JpaRepository<ProviderConfigEntity, UUID> {

    /**
     * 查询指定租户和工作区下的配置，按更新时间倒序。
     */
    List<ProviderConfigEntity> findByTenantIdAndWorkspaceIdOrderByUpdatedAtDesc(
            UUID tenantId,
            UUID workspaceId
    );

    /**
     * 按 ID、租户和工作区查询配置，用于权限隔离。
     */
    Optional<ProviderConfigEntity> findByIdAndTenantIdAndWorkspaceId(
            UUID id,
            UUID tenantId,
            UUID workspaceId
    );
}
