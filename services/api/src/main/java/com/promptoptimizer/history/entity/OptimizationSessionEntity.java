package com.promptoptimizer.history.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 优化历史会话实体，对应 optimization_session 表。
 * 会话归属于单一租户和工作区，用于组织同一工作区中的相关优化记录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class OptimizationSessionEntity {

    /** 优化会话主键，被 optimization_record.session_id 引用。 */
    private UUID id;

    /** 所属租户主键，用于租户隔离。 */
    private UUID tenantId;

    /** 所属工作区主键；历史查询需限制在该工作区。 */
    private UUID workspaceId;

    /** 创建该会话的用户主键。 */
    private UUID createdBy;

    /** 会话展示标题，可为空。 */
    private String title;

    /** ACTIVE=可继续使用；ARCHIVED=已归档；合法值与数据库 CHECK 一致。 */
    private String status = "ACTIVE";

    /** 会话创建时间，使用 UTC 时区。 */
    private OffsetDateTime createdAt;

    /** 会话最后更新时间，使用 UTC 时区。 */
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
