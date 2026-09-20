package com.promptoptimizer.history.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.exception.ResourceNotFoundException;
import com.promptoptimizer.context.api.ContextAnalysisRequest;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.api.EnhancementOptions;
import com.promptoptimizer.enhancement.api.OptimizationRequest;
import com.promptoptimizer.enhancement.api.PermissionPolicyInput;
import com.promptoptimizer.enhancement.api.PlanConfirmation;
import com.promptoptimizer.enhancement.application.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.history.domain.OptimizationHistoryDetail;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.domain.OptimizationHistorySummary;
import com.promptoptimizer.history.domain.ReoptimizationResult;
import com.promptoptimizer.history.infrastructure.OptimizationRecordEntity;
import com.promptoptimizer.history.infrastructure.OptimizationRecordRepository;
import com.promptoptimizer.history.infrastructure.OptimizationSessionEntity;
import com.promptoptimizer.history.infrastructure.OptimizationSessionRepository;
import com.promptoptimizer.identity.application.ActorIdentity;
import com.promptoptimizer.identity.application.CurrentActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 基于 JPA 的历史服务实现，保存脱敏摘要，并把重新优化委托给增强编排器。
 */
@Service
@ConditionalOnProperty(prefix = "app.history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JpaOptimizationHistoryService implements OptimizationHistoryService {

    private static final String SESSION_STATUS_ACTIVE = "ACTIVE";
    private static final String DEFAULT_SESSION_TITLE = "默认优化会话";
    private static final int PREVIEW_LENGTH = 160;

    private final OptimizationRecordRepository recordRepository;
    private final OptimizationSessionRepository sessionRepository;
    private final EnhancementOrchestrator orchestrator;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;

    /**
     * 注入历史仓库、增强编排器和当前认证主体。
     */
    public JpaOptimizationHistoryService(
            OptimizationRecordRepository recordRepository,
            OptimizationSessionRepository sessionRepository,
            EnhancementOrchestrator orchestrator,
            CurrentActor currentActor,
            ObjectMapper objectMapper
    ) {
        this.recordRepository = recordRepository;
        this.sessionRepository = sessionRepository;
        this.orchestrator = orchestrator;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询历史摘要。
     */
    @Override
    public OptimizationHistoryPage list(int page, int size) {
        ActorIdentity context = currentActor.require();
        Page<OptimizationRecordEntity> records = recordRepository
                .findByTenantIdAndWorkspaceIdOrderByCreatedAtDesc(
                        context.tenantId(),
                        context.workspaceId(),
                        PageRequest.of(page, size)
                );
        List<OptimizationHistorySummary> items = records.getContent().stream()
                .map(this::toSummary)
                .toList();
        return new OptimizationHistoryPage(
                items,
                records.getNumber(),
                records.getSize(),
                records.getTotalElements(),
                records.getTotalPages()
        );
    }

    /**
     * 查询单条详情。
     */
    @Override
    public OptimizationHistoryDetail get(UUID id) {
        return toDetail(findRecord(id));
    }

    /**
     * 删除单条记录。
     */
    @Override
    public void delete(UUID id) {
        OptimizationRecordEntity entity = findRecord(id);
        recordRepository.delete(entity);
    }

    /**
     * 把一次优化结果保存为历史记录，只保存脱敏后的上下文摘要。
     */
    @Override
    public UUID save(OptimizationRequest request, OptimizationResult result) {
        ActorIdentity context = currentActor.require();

        OptimizationRecordEntity entity = new OptimizationRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(context.tenantId());
        entity.setWorkspaceId(context.workspaceId());
        entity.setSessionId(ensureSession(context));
        entity.setCreatedBy(context.userId());
        entity.setTemplateCode(result.templateCode().name());
        entity.setRawPrompt(request.rawPrompt());
        entity.setOptimizedPrompt(result.optimizedPrompt());
        entity.setContextSnapshot(sanitizeContext(result.contextReport()));
        entity.setResultMetadata(buildResultMetadata(request, result));
        entity.setPermissionPolicy(toMap(request.permissionPolicy()));
        entity.setLatencyMs((int) Math.min(Integer.MAX_VALUE, result.latencyMs()));

        return recordRepository.save(entity).getId();
    }

    /**
     * 使用历史输入再次优化并保存为新记录。
     */
    @Override
    public ReoptimizationResult reoptimize(UUID id) {
        OptimizationRecordEntity source = findRecord(id);
        OptimizationRequest request = rebuildRequest(source);
        OptimizationResult result = orchestrator.optimize(request);
        UUID newRecordId = save(request, result);
        return new ReoptimizationResult(newRecordId, result);
    }

    /**
     * 查找当前工作区内的记录，找不到时返回统一的资源不存在错误。
     */
    private OptimizationRecordEntity findRecord(UUID id) {
        ActorIdentity context = currentActor.require();
        return recordRepository.findByIdAndTenantIdAndWorkspaceId(
                        id,
                        context.tenantId(),
                        context.workspaceId()
                )
                .orElseThrow(() -> new ResourceNotFoundException("优化记录不存在或无权访问"));
    }

    /**
     * 复用工作区默认会话，不存在时创建一个。
     */
    private UUID ensureSession(ActorIdentity context) {
        return sessionRepository
                .findFirstByTenantIdAndWorkspaceIdAndStatusOrderByCreatedAtAsc(
                        context.tenantId(),
                        context.workspaceId(),
                        SESSION_STATUS_ACTIVE
                )
                .map(OptimizationSessionEntity::getId)
                .orElseGet(() -> createDefaultSession(context));
    }

    /**
     * 创建 MVP 阶段的默认优化会话。
     */
    private UUID createDefaultSession(ActorIdentity context) {
        OptimizationSessionEntity session = new OptimizationSessionEntity();
        session.setId(UUID.randomUUID());
        session.setTenantId(context.tenantId());
        session.setWorkspaceId(context.workspaceId());
        session.setCreatedBy(context.userId());
        session.setTitle(DEFAULT_SESSION_TITLE);
        session.setStatus(SESSION_STATUS_ACTIVE);
        return sessionRepository.save(session).getId();
    }

    /**
     * 把列表实体转换为摘要。
     */
    private OptimizationHistorySummary toSummary(OptimizationRecordEntity entity) {
        Map<String, Object> metadata = entity.getResultMetadata();
        return new OptimizationHistorySummary(
                entity.getId(),
                entity.getTemplateCode(),
                preview(entity.getRawPrompt()),
                stringValue(metadata.get("provider")),
                stringValue(metadata.get("model")),
                Boolean.TRUE.equals(metadata.get("mock")),
                entity.getLatencyMs(),
                entity.getCreatedAt()
        );
    }

    /**
     * 把列表实体转换为完整详情。
     */
    private OptimizationHistoryDetail toDetail(OptimizationRecordEntity entity) {
        Map<String, Object> metadata = entity.getResultMetadata();
        Map<String, Object> options = toMap(metadata.get("enhancementOptions"));

        return new OptimizationHistoryDetail(
                entity.getId(),
                entity.getRawPrompt(),
                entity.getOptimizedPrompt(),
                convertList(metadata.get("sections"), new TypeReference<List<PromptSection>>() {
                }),
                entity.getContextSnapshot() == null ? Map.of() : entity.getContextSnapshot(),
                convertList(metadata.get("ambiguities"), new TypeReference<List<String>>() {
                }),
                convertList(metadata.get("appliedConstraints"), new TypeReference<List<String>>() {
                }),
                entity.getTemplateCode(),
                stringValue(metadata.get("provider")),
                stringValue(metadata.get("model")),
                Boolean.TRUE.equals(metadata.get("mock")),
                entity.getLatencyMs(),
                entity.getCreatedAt(),
                Boolean.TRUE.equals(options.get("includePermissionBoundaries")),
                Boolean.TRUE.equals(options.get("includeExamples")),
                convertList(metadata.get("conversationHistory"), new TypeReference<List<ConversationMessage>>() {
                }),
                entity.getPermissionPolicy()
        );
    }

    /**
     * 组装结果元数据，包含段落、歧义、约束、Provider 和增强选项。
     */
    private Map<String, Object> buildResultMetadata(
            OptimizationRequest request,
            OptimizationResult result
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sections", toList(result.sections()));
        metadata.put("ambiguities", result.ambiguities());
        metadata.put("appliedConstraints", result.appliedConstraints());
        metadata.put("provider", result.provider().provider());
        metadata.put("model", result.provider().model());
        metadata.put("mock", result.provider().mock());
        metadata.put("enhancementOptions", Map.of(
                "templateCode", result.templateCode().name(),
                "includeConversationHistory", Boolean.TRUE.equals(request.enhancement().includeConversationHistory()),
                "includePermissionBoundaries", Boolean.TRUE.equals(request.enhancement().includePermissionBoundaries()),
                "includeExamples", Boolean.TRUE.equals(request.enhancement().includeExamples())
        ));
        metadata.put(
                "conversationHistory",
                Boolean.TRUE.equals(request.enhancement().includeConversationHistory())
                        ? toList(request.conversationHistory())
                        : List.of()
        );
        if (request.planConfirmation() != null) {
            metadata.put("planConfirmation", toMap(request.planConfirmation()));
        }
        return metadata;
    }

    /**
     * 只保存不包含文件正文的上下文摘要，避免把用户文件内容持久化。
     */
    private Map<String, Object> sanitizeContext(ContextSnapshot snapshot) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("customDescription", snapshot.customDescription());
        summary.put("technologyStack", toList(snapshot.technologyStack()));
        summary.put("dependencies", toList(snapshot.dependencies()));
        summary.put("directoryTree", snapshot.directoryTree());
        summary.put("warnings", snapshot.warnings());
        summary.put("redactions", snapshot.redactions());
        summary.put("analysisVersion", snapshot.analysisVersion());
        return summary;
    }

    /**
     * 从保存的元数据重建优化请求，文件正文不再保留，只使用脱敏摘要。
     */
    private OptimizationRequest rebuildRequest(OptimizationRecordEntity entity) {
        Map<String, Object> metadata = entity.getResultMetadata();
        Map<String, Object> options = toMap(metadata.get("enhancementOptions"));
        Map<String, Object> context = entity.getContextSnapshot() == null
                ? Map.of()
                : entity.getContextSnapshot();

        TemplateCode templateCode = parseTemplateCode(stringValue(options.get("templateCode")));
        EnhancementOptions enhancement = new EnhancementOptions(
                templateCode,
                Boolean.TRUE.equals(options.get("includeConversationHistory")),
                Boolean.TRUE.equals(options.get("includePermissionBoundaries")),
                Boolean.TRUE.equals(options.get("includeExamples"))
        );
        List<ConversationMessage> conversation = convertList(
                metadata.get("conversationHistory"),
                new TypeReference<List<ConversationMessage>>() {
                }
        );
        PermissionPolicyInput policy = objectMapper.convertValue(
                entity.getPermissionPolicy(),
                PermissionPolicyInput.class
        );
        PlanConfirmation savedPlanConfirmation = metadata.containsKey("planConfirmation")
                ? objectMapper.convertValue(metadata.get("planConfirmation"), PlanConfirmation.class)
                : null;
        // 历史重新优化保留已确认事实，但不复用已经过期的短期 planId/contextId。
        PlanConfirmation planConfirmation = savedPlanConfirmation == null
                ? null
                : new PlanConfirmation(savedPlanConfirmation.answers());

        return new OptimizationRequest(
                entity.getRawPrompt(),
                new ContextAnalysisRequest(stringValue(context.get("customDescription")), List.of()),
                enhancement,
                conversation,
                policy == null ? PermissionPolicyInput.empty() : policy,
                planConfirmation
        );
    }

    /**
     * 生成列表页使用的原始提示词预览。
     */
    private String preview(String rawPrompt) {
        String normalized = rawPrompt.replace("\r", " ").replace("\n", " ").trim();
        if (normalized.length() <= PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_LENGTH) + "…";
    }

    /**
     * 把可能为空的字符串值转换为安全字符串。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 把任意对象转换为 Map 结构。
     */
    private Map<String, Object> toMap(Object value) {
        return value == null
                ? Map.of()
                : objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
                });
    }

    /**
     * 把任意对象转换为 List 结构。
     */
    private List<Object> toList(Object value) {
        return value == null
                ? List.of()
                : objectMapper.convertValue(value, new TypeReference<List<Object>>() {
                });
    }

    /**
     * 把元数据中的列表值转换为强类型列表。
     */
    private <T> List<T> convertList(Object value, TypeReference<List<T>> type) {
        return value == null ? List.of() : objectMapper.convertValue(value, type);
    }

    /**
     * 把字符串模板编码转换为枚举，兼容历史数据为空的情况。
     */
    private TemplateCode parseTemplateCode(String value) {
        try {
            return value == null || value.isBlank()
                    ? TemplateCode.AUTO
                    : TemplateCode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return TemplateCode.AUTO;
        }
    }
}
