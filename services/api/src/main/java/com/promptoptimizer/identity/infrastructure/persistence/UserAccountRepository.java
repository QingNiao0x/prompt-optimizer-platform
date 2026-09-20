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

    Optional<UserAccountEntity> findByEmailIgnoreCase(String email);

    /**
     * 只从服务端成员关系选择同租户的有效工作区，不能接受客户端工作区标识。
     */
    @Query(value = """
            SELECT wm.workspace_id
            FROM workspace_member wm
            JOIN workspace w ON w.id = wm.workspace_id
            WHERE wm.user_id = :userId
              AND w.tenant_id = :tenantId
              AND w.status = 'ACTIVE'
            ORDER BY CASE wm.role
                         WHEN 'OWNER' THEN 0
                         WHEN 'EDITOR' THEN 1
                         ELSE 2
                     END,
                     wm.joined_at ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findDefaultWorkspaceId(
            @Param("userId") UUID userId,
            @Param("tenantId") UUID tenantId
    );
}
