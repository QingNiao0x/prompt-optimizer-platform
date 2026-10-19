package com.promptoptimizer.history.service.impl;

import com.promptoptimizer.history.service.OptimizationHistoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.promptoptimizer.enhancement.domain.PromptDocument;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.history.domain.DocumentReoptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.history.domain.OptimizationHistoryDetail;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.domain.OptimizationHistorySummary;
import com.promptoptimizer.history.domain.ReoptimizationResult;
import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private NaturalEnhancementService naturalEnhancement;

    /** 独立生成器不依赖历史服务，避免持久化与模型调用形成循环。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void setNaturalEnhancement(NaturalEnhancementService naturalEnhancement) {
        this.naturalEnhancement = naturalEnhancement;
    }

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
            int current,
            int size,
            String keyword,
            OffsetDateTime createdFrom,
            OffsetDateTime createdToExclusive
    ) {
        return listRecords(current, size, keyword, createdFrom, createdToExclusive, false);
    }

    /** V2页面在同一列表中管理自然与旧段落记录，不拆库、不重写旧历史。 */
    @Override
    @Transactional(readOnly = true)
    public OptimizationHistoryPage listAll(int current, int size, String keyword,
            OffsetDateTime createdFrom, OffsetDateTime createdToExclusive) {
        return listRecords(current, size, keyword, createdFrom, createdToExclusive, true);
    }

    /** 在SQL分页前选择协议范围，查询、计数及租户隔离使用同一条件。 */
    private OptimizationHistoryPage listRecords(int current, int size, String keyword,
            OffsetDateTime createdFrom, OffsetDateTime createdToExclusive, boolean includeNatural) {
        ActorIdentity context = currentActor.require();
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasDateRange = createdFrom != null && createdToExclusive != null;
        String escapedKeyword = hasKeyword ? escapeLikePattern(keyword) : null;
        OffsetDateTime rangeStart = hasDateRange ? createdFrom : null;
        OffsetDateTime rangeEnd = hasDateRange ? createdToExclusive : null;
        Page<OptimizationRecordEntity> page = new Page<>(current, size);
        // 摘要 SQL 含 left 与 jsonb_build_object，计数优化会把 total 改成 0。
        page.setOptimizeCountSql(false);
        IPage<OptimizationRecordEntity> result = includeNatural
                ? recordMapper.selectAllPageByScope(page, context.tenantId(), context.workspaceId(), escapedKeyword, rangeStart, rangeEnd)
                : recordMapper.selectPageByScope(page, context.tenantId(), context.workspaceId(), escapedKeyword, rangeStart, rangeEnd);
        List<OptimizationHistorySummary> records = result.getRecords().stream().map(this::toSummary).toList();
        return new OptimizationHistoryPage(
                records, result.getTotal(), result.getSize(), result.getCurrent(), result.getPages());
    }

    /**
     * 查询单条详情。
     */
    @Override
    @Transactional(readOnly = true)
    public OptimizationHistoryDetail get(UUID id) {
        OptimizationRecordEntity record = findRecord(id);
        requireLegacy(record);
        return toDetail(record);
    }

    @Override
    @Transactional(readOnly = true)
    public OptimizationHistoryDetail getDocument(UUID id) { return toDetail(findRecord(id)); }

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
        return saveDocument(request, PromptDocument.legacy(result));
    }

    /** 同一事务保存原生正文及链路身份，TEXT不保存伪造段落。 */
    @Override
    @Transactional
    public UUID saveDocument(OptimizationRequest request, PromptDocument result) {
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
        requireLegacy(source);
        OptimizationRequest request = rebuildRequest(source);
        OptimizationResult result = orchestrator.optimize(request);
        UUID newRecordId = save(request, result);
        return new ReoptimizationResult(newRecordId, result);
    }

    /** 历史依据持久化链路分发；旧COMPACT仍走旧链，不从展示样式猜测。 */
    @Override
    public DocumentReoptimizationResult reoptimizeDocument(UUID id) {
        OptimizationRecordEntity source = findRecord(id);
        boolean natural = PromptDocument.ResultKind.TEXT.name().equals(source.getResultMetadata().get("resultKind"));
        Object savedVersion = source.getResultMetadata().get("chainVersion");
        boolean supportedNatural = natural
                && PromptDocument.NATURAL_PIPELINE.equals(source.getResultMetadata().get("generationPipeline"))
                && savedVersion instanceof String version
                && com.promptoptimizer.provider.domain.NaturalPromptInstructions.supports(version);
        // 只分发明确支持的协议组合。缺字段的旧记录按旧版本处理，显式未知版本绝不猜测。
        if (!supportedNatural && !supportedLegacy(source)) {
            throw new com.promptoptimizer.common.exception.UnsupportedResultVersionException("该记录的生成版本暂不支持再次增强，请恢复并编辑原始需求。");
        }
        OptimizationRequest request = rebuildRequest(source);
        if (natural && naturalEnhancement == null) throw new IllegalStateException("自然增强服务未装配");
        PromptDocument result;
        // 新自然协议保存字符串版本；不能把损坏的数值元数据隐式转换成受支持版本。
        if (natural) result = naturalEnhancement.optimize(request, (String) savedVersion);
        else {
            OptimizationResult generated = orchestrator.optimize(request);
            result = PromptDocument.STRUCTURED_VERSION.equals(source.getResultMetadata().get("chainVersion"))
                    ? PromptDocument.structured(generated) : PromptDocument.legacy(generated);
        }
        UUID recordId = saveDocument(request, result);
        return new DocumentReoptimizationResult(recordId, result.withRecordId(recordId));
    }

    /** 自然编辑保持原生成快照，校验固定权限后以CAS保存，不调用模型或重建四要素。 */
    @Override
    @Transactional
    public com.promptoptimizer.history.domain.PromptTextEditResult editText(UUID id,
            com.promptoptimizer.history.dto.PromptTextEditRequest request) {
        OptimizationRecordEntity source = findRecord(id);
        Map<String, Object> metadata = new LinkedHashMap<>(source.getResultMetadata());
        if (!PromptDocument.ResultKind.TEXT.name().equals(metadata.get("resultKind"))) {
            throw new InvalidOptimizationRequestException("该记录不支持自然正文编辑。");
        }
        if (new com.promptoptimizer.common.security.SensitiveValueScanner().containsCredential(request.optimizedPrompt())) {
            throw new InvalidOptimizationRequestException("编辑正文包含疑似敏感凭据，请脱敏后保存。");
        }
        ConstraintBundle groups = metadata.get("constraintGroups") == null ? null
                : objectMapper.convertValue(metadata.get("constraintGroups"), ConstraintBundle.class);
        if (groups == null || java.util.stream.Stream.concat(groups.platformMandatory().stream(), groups.additionalPermissions().stream())
                .anyMatch(rule -> !request.optimizedPrompt().contains(rule))) {
            throw new InvalidOptimizationRequestException("请保留本次结果的事实和权限边界，修改稿已保留。");
        }
        metadata.putIfAbsent("generatedPrompt", source.getOptimizedPrompt());
        long revision = request.expectedRevision() + 1;
        metadata.put("editRevision", revision);
        metadata.put("editedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        ActorIdentity context = currentActor.require();
        int updated = recordMapper.updateNaturalText(id, context.tenantId(), context.workspaceId(),
                request.expectedRevision(), request.optimizedPrompt(), metadata);
        if (updated == 0) throw new InvalidOptimizationRequestException("记录已变化或被删除，请刷新后核对；本地修改稿已保留。");
        return new com.promptoptimizer.history.domain.PromptTextEditResult(request.optimizedPrompt(), revision);
    }

    /** 旧客户端必须显式升级协议，不能把新正文放入旧四段编辑流程而丢稿。 */
    private void requireLegacy(OptimizationRecordEntity record) {
        if (!supportedLegacy(record)) {
            throw new com.promptoptimizer.common.exception.UnsupportedResultVersionException("当前V1接口不支持此结果版本，请使用V2接口。");
        }
    }

    /** 兼容缺省旧元数据及已知五模块版本，不将未知形态或链路降级成旧结果。 */
    private boolean supportedLegacy(OptimizationRecordEntity record) {
        var metadata = record.getResultMetadata();
        return PromptDocument.ResultKind.SECTIONS.name().equals(metadataValue(metadata, "resultKind", "SECTIONS"))
                && PromptDocument.LEGACY_PIPELINE.equals(metadataValue(metadata, "generationPipeline", PromptDocument.LEGACY_PIPELINE))
                && (PromptDocument.LEGACY_VERSION.equals(metadataValue(metadata, "chainVersion", PromptDocument.LEGACY_VERSION))
                        || PromptDocument.STRUCTURED_VERSION.equals(metadata.get("chainVersion")));
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
                entity.getCreatedAt(),
                stringValue(metadata.get("modelVersion")),
                metadataValue(metadata, "resultKind", PromptDocument.ResultKind.SECTIONS.name()),
                metadataValue(metadata, "generationPipeline", PromptDocument.LEGACY_PIPELINE),
                metadataValue(metadata, "chainVersion", PromptDocument.LEGACY_VERSION),
                stringValue(metadata.get("framework"))
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
                entity.getPermissionPolicy(),
                stringValue(metadata.get("modelVersion")),
                "COMPACT".equals(metadata.get("promptFormat"))
                        ? com.promptoptimizer.enhancement.domain.PromptFormat.COMPACT
                        : com.promptoptimizer.enhancement.domain.PromptFormat.STRUCTURED,
                metadata.get("constraintGroups") == null ? null
                        : objectMapper.convertValue(metadata.get("constraintGroups"), ConstraintBundle.class),
                metadataValue(metadata, "resultKind", PromptDocument.ResultKind.SECTIONS.name()),
                metadataValue(metadata, "generationPipeline", PromptDocument.LEGACY_PIPELINE),
                metadataValue(metadata, "chainVersion", PromptDocument.LEGACY_VERSION),
                stringValue(metadata.get("framework"))
        );
    }

    /**
     * 组装结果元数据，包含段落、歧义、约束、Provider 和增强选项。
     */
    private Map<String, Object> buildResultMetadata(
            OptimizationRequest request,
            PromptDocument result
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result.resultKind() == PromptDocument.ResultKind.SECTIONS) metadata.put("sections", toList(result.sections()));
        metadata.put("resultKind", result.resultKind().name());
        metadata.put("generationPipeline", result.generationPipeline());
        metadata.put("chainVersion", result.chainVersion());
        metadata.put("selectedModelId", result.provider().mock() ? request.modelId() : result.provider().model());
        if (result.framework() != null) metadata.put("framework", result.framework());
        metadata.put("promptFormat", result.promptFormat().name());
        metadata.put("ambiguities", result.ambiguities());
        metadata.put("appliedConstraints", result.appliedConstraints());
        // 来源归属只来自本次服务端组装；旧历史留空，不从扁平规则反推或回写正文。
        if (result.constraintGroups() != null) metadata.put("constraintGroups", toMap(result.constraintGroups()));
        metadata.put("provider", result.provider().provider());
        metadata.put("model", result.provider().model());
        // 版本名称在调用时固化；不能通过关联当前目录让历史随管理员改名而变化。
        metadata.put("modelVersion", result.provider().modelVersion());
        metadata.put("mock", result.provider().mock());
        Map<String, Object> enhancementOptions = new LinkedHashMap<>(Map.of(
                "templateCode", result.templateCode().name(),
                "includeConversationHistory", Boolean.TRUE.equals(request.enhancement().includeConversationHistory()),
                "includePermissionBoundaries", Boolean.TRUE.equals(request.enhancement().includePermissionBoundaries()),
                "includeExamples", Boolean.TRUE.equals(request.enhancement().includeExamples())
        ));
        // 请求偏好与最终呈现分开保存；自动选择的结果不能升级为用户的显式排版选择。
        if (request.enhancement().promptFormat() != null) {
            enhancementOptions.put("promptFormat", request.enhancement().promptFormat().name());
        }
        metadata.put("enhancementOptions", enhancementOptions);
        metadata.put(
                "conversationHistory",
                Boolean.TRUE.equals(request.enhancement().includeConversationHistory())
                        ? toList(request.conversationHistory())
                        : List.of()
        );
        if (request.planConfirmation() != null) {
            PlanConfirmation confirmation = request.planConfirmation();
            // 自然链题干由会话服务恢复，历史必须保存同一权威题答，不能把客户端伪题干带入再次增强。
            if (result.confirmedPlanAnswers() != null) {
                confirmation = new PlanConfirmation(confirmation.planId(), confirmation.planningContext(), result.confirmedPlanAnswers());
            }
            metadata.put("planConfirmation", toMap(confirmation));
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
                Boolean.TRUE.equals(options.get("includeExamples")),
                requestedPromptFormat(options.get("promptFormat"))
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
                null,
                PromptDocument.NATURAL_PIPELINE.equals(metadata.get("generationPipeline")) ? stringValue(metadata.get("selectedModelId")) : null
        );
    }

    /** 只恢复已保存的有效请求偏好；旧记录或非枚举值继续使用自动模式，不借用结果的展示值。 */
    private PromptFormat requestedPromptFormat(Object value) {
        if (PromptFormat.COMPACT.name().equals(value)) return PromptFormat.COMPACT;
        if (PromptFormat.STRUCTURED.name().equals(value)) return PromptFormat.STRUCTURED;
        return null;
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

    /** SQL列表投影会把旧记录的缺失键变成JSON null，两种情形都回退旧协议；显式未知值仍原样保留。 */
    private String metadataValue(Map<String, Object> metadata, String key, String fallback) {
        String value = stringValue(metadata.get(key));
        return value.isBlank() ? fallback : value;
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
