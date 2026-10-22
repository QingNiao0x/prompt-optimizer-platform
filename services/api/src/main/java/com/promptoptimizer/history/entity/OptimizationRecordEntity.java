package com.promptoptimizer.history.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 优化历史实体，对应 optimization_record 表。
 * 记录按租户、工作区、会话和创建者归属；只在用户保存历史时持久化，不保存上传文件正文。
 * 上下文快照仅保留脱敏摘要，已逻辑删除的记录由历史 Mapper 的 XML 查询显式过滤。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class OptimizationRecordEntity {

    /** 优化历史记录主键。 */
    private UUID id;

    /** 所属租户主键；历史查询必须校验租户范围。 */
    private UUID tenantId;

    /** 所属工作区主键；历史查询必须校验工作区范围。 */
    private UUID workspaceId;

    /** 所属优化会话主键。 */
    private UUID sessionId;

    /** 发起本次优化的用户主键。 */
    private UUID createdBy;

    /** 最终采用的模板编码；取值见 {@link com.promptoptimizer.enhancement.domain.TemplateCode}，不保存请求侧 AUTO。 */
    private String templateCode;

    /** 用户提交的原始提示词；仅用户保存历史时持久化。 */
    private String rawPrompt;

    /** 本次增强的最终自然或分节正文；读取时不重新组装，不表示已执行下游任务。 */
    private String optimizedPrompt;

    /** 脱敏上下文 JSON 对象，含项目描述、技术栈/依赖条目、目录/警告/脱敏字符串数组和分析版本；不含文件正文。 */
    private Map<String, Object> contextSnapshot;

    /**
     * 结果 JSON 对象，保存段落、歧义/约束数组、Provider/调用 ID、modelVersion、增强选项、会话及可选 Plan 确认。
     * enhancementOptions 当前包含 templateCode、includeConversationHistory、includePermissionBoundaries；
     * 可选 promptFormat 仅保存显式排版选择，旧 includeExamples 键不再读写，历史正文及原有 JSON 不回改。
     * 顶层 promptFormat 为 COMPACT/STRUCTURED，旧记录缺省按 STRUCTURED 展示；请求缺省表示再次增强时自动选择。
     * 可选 constraintGroups 含 platformMandatory、taskSpecific、additionalPermissions 三个字符串数组，均来自服务端；
     * 旧记录缺省表示来源未知，不用于关闭安全过滤。JSON 键和值域由应用校验，数据库不强制结构。
     */
    // V2可选resultKind(TEXT/SECTIONS)、generationPipeline(NATURAL/LEGACY)、chainVersion及framework；
    // TEXT没有伪造sections，缺少链路字段的旧COMPACT仍按LEGACY读取。数据库不强制JSON键结构。
    // NATURAL/1—3冻结旧指导，默认NATURAL/4包含同次覆盖检查；7—14为历史候选，15为独立补全准备，16/17为含/不含准备的保真审阅对照，均非默认策略。
    // 再次增强按保存版本分发；内部任务理解、覆盖、示范引用均不持久化，不把候选版本存在视为质量通过。
    // 可选selectedModelId为服务端公开模型标识；editRevision缺省0，generatedPrompt保留首次编辑前正文，editedAt为UTC时间。
    private Map<String, Object> resultMetadata = new LinkedHashMap<>();

    /** 权限策略 JSON 对象，包含 protectedPaths 与 requireConfirmationFor 两个字符串数组；结构由请求 DTO 校验。 */
    private Map<String, Object> permissionPolicy = new LinkedHashMap<>();

    /** 本次优化总耗时，单位毫秒；数据库约束禁止负数。 */
    private Integer latencyMs;

    /** 可选的数据保留截止时间，使用 UTC 时区。 */
    private OffsetDateTime retentionUntil;

    /** 逻辑删除时间；NULL 表示正常可见，非 NULL 表示常规查询应隐藏。 */
    private OffsetDateTime deletedAt;

    /** 历史记录创建时间，使用 UTC 时区。 */
    private OffsetDateTime createdAt;

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

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
