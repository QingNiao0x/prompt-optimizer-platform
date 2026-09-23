package com.promptoptimizer.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户登录身份与默认工作区的数据访问接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface UserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {

    /**
     * 查询用户在租户内的默认可用工作区。
     * 当前只开放个人所有者工作区；团队角色授权上线前不把 EDITOR/VIEWER 当成所有者。
     */
    @Query(value = """
            SELECT wm.workspace_id
            FROM workspace_member wm
            JOIN workspace w ON w.id = wm.workspace_id
            JOIN tenant t ON t.id = w.tenant_id
            WHERE wm.user_id = :userId
              AND w.tenant_id = :tenantId
              AND w.status = 'ACTIVE'
              AND t.status = 'ACTIVE'
              AND wm.role = 'OWNER'
            ORDER BY wm.joined_at ASC, wm.workspace_id ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findDefaultWorkspaceId(
            @Param("userId") UUID userId,
            @Param("tenantId") UUID tenantId
    );
}
