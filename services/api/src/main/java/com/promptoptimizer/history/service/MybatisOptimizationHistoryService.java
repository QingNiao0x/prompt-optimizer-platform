package com.promptoptimizer.history.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.exception.ResourceNotFoundException;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanConfirmation;
import com.promptoptimizer.enhancement.service.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.history.domain.OptimizationHistoryDetail;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.domain.OptimizationHistorySummary;
import com.promptoptimizer.history.domain.ReoptimizationResult;
import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 基于 MyBatis Mapper 的历史服务实现，保存脱敏摘要，并把重新优化委托给增强编排器。
 */
@Service
@ConditionalOnProperty(prefix = "app.history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MybatisOptimizationHistoryService implements OptimizationHistoryService {

    private static final String SESSION_STATUS_ACTIVE = "ACTIVE";
    private static final String DEFAULT_SESSION_TITLE = "默认优化会话";
    private static final int PREVIEW_LENGTH = 160;

    private final OptimizationRecordMapper recordMapper;
    private final OptimizationHistoryPersistenceService persistenceService;
    private final EnhancementOrchestrator orchestrator;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;

    /**
     * 注入历史仓库、增强编排器和当前认证主体。
     */
    public MybatisOptimizationHistoryService(
            OptimizationRecordMapper recordMapper,
            OptimizationHistoryPersistenceService persistenceService,
            EnhancementOrchestrator orchestrator,
            CurrentActor currentActor,
            ObjectMapper objectMapper
    ) {
        this.recordMapper = recordMapper;
        this.persistenceService = persistenceService;
        this.orchestrator = orchestrator;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询历史摘要。
     */
    @Override
    @Transactional(readOnly = true)
    public OptimizationHistoryPage list(
            int page,
            int size,
            String keyword,
            OffsetDateTime createdFrom,
            OffsetDateTime createdToExclusive
    ) {
        ActorIdentity context = currentActor.require();
        Page<OptimizationRecordEntity> pageRequest = new Page<>((long) page + 1L, size);
        // 列表 SQL 含预览截断和 jsonb_build_object，默认计数改写会得到 0。
        pageRequest.setOptimizeCountSql(false);
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasDateRange = createdFrom != null && createdToExclusive != null;
        IPage<OptimizationRecordEntity> records = recordMapper.selectPageByScope(
                pageRequest,
                context.tenantId(),
                context.workspaceId(),
                hasKeyword ? escapeLikePattern(keyword) : null,
                hasDateRange ? createdFrom : null,
                hasDateRange ? createdToExclusive : null
        );
        List<OptimizationHistorySummary> items = records.getRecords().stream()
                .map(this::toSummary)
                .toList();
        return new OptimizationHistoryPage(
                items,
                Math.toIntExact(records.getCurrent() - 1L),
                Math.toIntExact(records.getSize()),
                records.getTotal(),
                Math.toIntExact(records.getPages())
        );
    }

    /**
     * 查询单条详情。
     */
    @Override
    @Transactional(readOnly = true)
    public OptimizationHistoryDetail get(UUID id) {
        return toDetail(findRecord(id));
    }

    /** 在当前租户和工作区内逻辑删除历史记录；已删除或越权记录统一按不存在处理。 */
    @Override
    @Transactional
    public void delete(UUID id) {
        ActorIdentity context = currentActor.require();
        int updated = recordMapper.markDeletedByIdAndScope(
                id,
                context.tenantId(),
                context.workspaceId(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        if (updated == 0) {
            throw new ResourceNotFoundException("优化记录不存在或无权访问");
        }
    }

    /**
     * 把一次优化结果保存为历史记录，只保存脱敏后的上下文摘要。
     */
    @Override
    @Transactional
    public UUID save(OptimizationRequest request, OptimizationResult result) {
        ActorIdentity context = currentActor.require();

        OptimizationRecordEntity entity = new OptimizationRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(context.tenantId());
        entity.setWorkspaceId(context.workspaceId());
        entity.setCreatedBy(context.userId());
        entity.setTemplateCode(result.templateCode().name());
        entity.setRawPrompt(request.rawPrompt());
        entity.setOptimizedPrompt(result.optimizedPrompt());
        entity.setContextSnapshot(sanitizeContext(result.contextReport()));
        entity.setResultMetadata(buildResultMetadata(request, result));
        entity.setPermissionPolicy(toMap(request.permissionPolicy()));
        entity.setLatencyMs((int) Math.min(Integer.MAX_VALUE, result.latencyMs()));

        return persistenceService.persist(entity, context);
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
        OptimizationRecordEntity record = recordMapper.selectByIdAndScope(
                id, context.tenantId(), context.workspaceId());
        if (record == null) {
            throw new ResourceNotFoundException("优化记录不存在或无权访问");
        }
        return record;
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
        // 历史回答是已保存的用户事实，不能伪装成仍有效的短期 Plan 会话。
        String rawPrompt = entity.getRawPrompt();
        if (savedPlanConfirmation != null && !savedPlanConfirmation.answers().isEmpty()) {
            String confirmedFacts = savedPlanConfirmation.answers().stream()
                    .map(answer -> answer.question() + "：" + answer.answer())
                    .collect(java.util.stream.Collectors.joining("\n"));
            rawPrompt += "\n\n用户此前已确认的信息：\n" + confirmedFacts;
            if (rawPrompt.length() > 8_000) {
                throw new InvalidOptimizationRequestException("历史需求与已确认信息超过 8,000 字符，请编辑后重新增强。");
            }
        }

        return new OptimizationRequest(
                rawPrompt,
                new ContextAnalysisRequest(stringValue(context.get("customDescription")), List.of()),
                enhancement,
                conversation,
                policy == null ? PermissionPolicyInput.empty() : policy,
                null
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

    /** 转义 LIKE 通配符，使历史关键字继续按字面子串匹配。 */
    private String escapeLikePattern(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
