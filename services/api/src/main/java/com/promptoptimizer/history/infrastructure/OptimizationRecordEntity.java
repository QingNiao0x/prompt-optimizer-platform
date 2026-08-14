package com.promptoptimizer.history.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 优化历史实体，对应 optimization_record 表，只保存脱敏上下文摘要，不保存原始文件内容。
 */
@Entity
@Table(name = "optimization_record")
public class OptimizationRecordEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "provider_config_id")
    private UUID providerConfigId;

    @Column(name = "template_code", nullable = false, length = 40)
    private String templateCode;

    @Column(name = "raw_prompt", nullable = false, columnDefinition = "text")
    private String rawPrompt;

    @Column(name = "optimized_prompt", nullable = false, columnDefinition = "text")
    private String optimizedPrompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> contextSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> resultMetadata = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permission_policy", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> permissionPolicy = new LinkedHashMap<>();

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "retention_until")
    private OffsetDateTime retentionUntil;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * 创建前初始化时间戳。
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

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

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getProviderConfigId() {
        return providerConfigId;
    }

    public void setProviderConfigId(UUID providerConfigId) {
        this.providerConfigId = providerConfigId;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public String getRawPrompt() {
        return rawPrompt;
    }

    public void setRawPrompt(String rawPrompt) {
        this.rawPrompt = rawPrompt;
    }

    public String getOptimizedPrompt() {
        return optimizedPrompt;
    }

    public void setOptimizedPrompt(String optimizedPrompt) {
        this.optimizedPrompt = optimizedPrompt;
    }

    public Map<String, Object> getContextSnapshot() {
        return contextSnapshot;
    }

    public void setContextSnapshot(Map<String, Object> contextSnapshot) {
        this.contextSnapshot = contextSnapshot == null ? null : new LinkedHashMap<>(contextSnapshot);
    }

    public Map<String, Object> getResultMetadata() {
        return resultMetadata;
    }

    public void setResultMetadata(Map<String, Object> resultMetadata) {
        this.resultMetadata = resultMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(resultMetadata);
    }

    public Map<String, Object> getPermissionPolicy() {
        return permissionPolicy;
    }

    public void setPermissionPolicy(Map<String, Object> permissionPolicy) {
        this.permissionPolicy = permissionPolicy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(permissionPolicy);
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public OffsetDateTime getRetentionUntil() {
        return retentionUntil;
    }

    public void setRetentionUntil(OffsetDateTime retentionUntil) {
        this.retentionUntil = retentionUntil;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
