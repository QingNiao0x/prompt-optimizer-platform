package com.promptoptimizer.history.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.promptoptimizer.common.exception.ResourceNotFoundException;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.service.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptDocument;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.policy.domain.PlatformConstraintRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 MyBatis 历史服务保留工作区边界、逻辑删除和脱敏持久化委托。 */
class MybatisOptimizationHistoryServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final ActorIdentity ACTOR = new ActorIdentity(
            USER_ID, TENANT_ID, WORKSPACE_ID, "demo@local", "Local Demo User");

    private final OptimizationRecordMapper recordMapper = mock(OptimizationRecordMapper.class);
    private final OptimizationHistoryPersistenceService persistenceService = mock(OptimizationHistoryPersistenceService.class);
    private final EnhancementOrchestrator orchestrator = mock(EnhancementOrchestrator.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private MybatisOptimizationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new MybatisOptimizationHistoryService(
                recordMapper, persistenceService, orchestrator, currentActor, objectMapper);
        when(currentActor.require()).thenReturn(ACTOR);
        when(persistenceService.persist(any(OptimizationRecordEntity.class), eq(ACTOR)))
                .thenAnswer(invocation -> ((OptimizationRecordEntity) invocation.getArgument(0)).getId());
    }

    @Test
    void savesSanitizedContextWithinCurrentActorScope() {
        UUID savedId = service.save(request(), resultWithFileContent().withModelVersion("DeepSeek-V4-Pro-0813"));

        ArgumentCaptor<OptimizationRecordEntity> captor = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(persistenceService).persist(captor.capture(), eq(ACTOR));
        OptimizationRecordEntity saved = captor.getValue();
        assertThat(savedId).isEqualTo(saved.getId());
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(saved.getContextSnapshot()).doesNotContainKey("fileSnippets");
        assertThat(saved.getResultMetadata()).containsEntry("modelVersion", "DeepSeek-V4-Pro-0813");
        assertThat(saved.getResultMetadata()).containsEntry("promptFormat", "STRUCTURED");
        assertThat(((Map<?, ?>) saved.getResultMetadata().get("enhancementOptions")).containsKey("includeExamples"))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retiredExampleOptionDoesNotReappearWhenReadingOrReoptimizingHistory(boolean oldValue) throws Exception {
        var record = recordEntity();
        var metadata = new java.util.LinkedHashMap<>(record.getResultMetadata());
        var options = Map.of("templateCode", "FEATURE_DEVELOPMENT", "includeConversationHistory", false,
                "includePermissionBoundaries", false, "includeExamples", oldValue);
        metadata.put("enhancementOptions", options);
        record.setResultMetadata(metadata);
        String savedBody = record.getOptimizedPrompt();
        when(recordMapper.selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(record);

        var detail = service.get(record.getId());
        assertThat(detail.optimizedPrompt()).isEqualTo(savedBody);
        assertThat(objectMapper.writeValueAsString(detail)).doesNotContain("includeExamples");

        when(orchestrator.optimize(any())).thenReturn(resultWithFileContent());
        service.reoptimize(record.getId());
        var captured = ArgumentCaptor.forClass(OptimizationRequest.class);
        verify(orchestrator).optimize(captured.capture());
        assertThat(captured.getValue().rawPrompt()).isEqualTo(record.getRawPrompt());
        assertThat(objectMapper.writeValueAsString(captured.getValue().enhancement()))
                .doesNotContain("includeExamples");
        assertThat(record.getOptimizedPrompt()).isEqualTo(savedBody);
    }

    @Test
    void readsOnlyRecordInCurrentTenantAndWorkspace() {
        OptimizationRecordEntity record = recordEntity();
        when(recordMapper.selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(record);

        var detail = service.get(record.getId());
        assertThat(detail.rawPrompt()).isEqualTo("给用户模块添加登录功能");
        assertThat(detail.modelVersion()).isEmpty();
        assertThat(detail.promptFormat()).isEqualTo(com.promptoptimizer.enhancement.domain.PromptFormat.STRUCTURED);
        assertThat(detail.constraintGroups()).isNull();
        verify(recordMapper).selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID);
    }

    @ParameterizedTest
    @EnumSource(com.promptoptimizer.enhancement.domain.PromptFormat.class)
    void retainsServerConstraintOriginsInHistoryAndReoptimizesTheOriginalTask(
            com.promptoptimizer.enhancement.domain.PromptFormat format) {
        var bundle = new com.promptoptimizer.policy.domain.ConstraintBundle(
                com.promptoptimizer.policy.domain.PlatformConstraintRules.forTask(true),
                List.of("保持接口兼容。"), List.of("禁止读取或输出受保护路径：private/**。"));
        var base = resultWithFileContent();
        var grouped = new OptimizationResult(base.optimizedPrompt(), base.sections(), base.contextReport(),
                base.ambiguities(), bundle.visibleConstraints(), base.templateCode(), base.provider(), base.latencyMs(),
                base.warnings(), base.evidenceCards(), format, bundle).withLatencyMs(53).withModelVersion("test-version");
        service.save(request(), grouped);
        var savedCaptor = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(persistenceService).persist(savedCaptor.capture(), eq(ACTOR));
        var saved = savedCaptor.getValue();
        when(recordMapper.selectByIdAndScope(saved.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(saved);
        var detail = service.get(saved.getId());
        assertThat(detail.constraintGroups()).isEqualTo(bundle);
        assertThat(detail.appliedConstraints()).containsExactlyElementsOf(bundle.visibleConstraints());
        assertThat(detail.optimizedPrompt()).isEqualTo(grouped.optimizedPrompt());
        assertThat(detail.sections()).isEqualTo(grouped.sections());
        when(orchestrator.optimize(any())).thenReturn(grouped);
        service.reoptimize(saved.getId());
        var requestCaptor = ArgumentCaptor.forClass(OptimizationRequest.class);
        verify(orchestrator).optimize(requestCaptor.capture());
        assertThat(requestCaptor.getValue().rawPrompt()).isEqualTo(request().rawPrompt());
        assertThat(requestCaptor.getValue().rawPrompt()).doesNotContain("private/**", "平台强制约束");
    }

    @ParameterizedTest
    @EnumSource(com.promptoptimizer.enhancement.domain.PromptFormat.class)
    void savesAndReusesExplicitFormatWhenReoptimizing(com.promptoptimizer.enhancement.domain.PromptFormat format) {
        var base = request();
        var explicit = new OptimizationRequest(base.rawPrompt(), base.context(),
                new EnhancementOptions(TemplateCode.AUTO, false, true, format), base.conversationHistory(),
                base.permissionPolicy());
        var baseResult = resultWithFileContent();
        var result = new OptimizationResult(baseResult.optimizedPrompt(), baseResult.sections(), baseResult.contextReport(),
                baseResult.ambiguities(), baseResult.appliedConstraints(), baseResult.templateCode(), baseResult.provider(),
                baseResult.latencyMs(), baseResult.warnings(), baseResult.evidenceCards(), format);
        service.save(explicit, result);
        var savedCaptor = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(persistenceService).persist(savedCaptor.capture(), eq(ACTOR));
        var saved = savedCaptor.getValue();
        assertThat(((Map<?, ?>) saved.getResultMetadata().get("enhancementOptions")).get("promptFormat"))
                .isEqualTo(format.name());
        assertThat(saved.getResultMetadata().get("promptFormat")).isEqualTo(format.name());
        when(recordMapper.selectByIdAndScope(saved.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(saved);
        when(orchestrator.optimize(any())).thenReturn(result);
        service.reoptimize(saved.getId());
        var requestCaptor = ArgumentCaptor.forClass(OptimizationRequest.class);
        verify(orchestrator).optimize(requestCaptor.capture());
        assertThat(requestCaptor.getValue().enhancement().promptFormat()).isEqualTo(format);
        assertThat(requestCaptor.getValue().rawPrompt()).isEqualTo(explicit.rawPrompt());
        verify(recordMapper).selectByIdAndScope(saved.getId(), TENANT_ID, WORKSPACE_ID);
    }

    @ParameterizedTest
    @EnumSource(com.promptoptimizer.enhancement.domain.PromptFormat.class)
    void oldResultFormatDoesNotBecomeAnExplicitPreference(com.promptoptimizer.enhancement.domain.PromptFormat format) {
        var saved = recordEntity();
        saved.getResultMetadata().put("promptFormat", format.name());
        when(recordMapper.selectByIdAndScope(saved.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(saved);
        when(orchestrator.optimize(any())).thenReturn(resultWithFileContent());
        service.reoptimize(saved.getId());
        var requestCaptor = ArgumentCaptor.forClass(OptimizationRequest.class);
        verify(orchestrator).optimize(requestCaptor.capture());
        assertThat(requestCaptor.getValue().enhancement().promptFormat()).isNull();
        var resavedCaptor = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(persistenceService).persist(resavedCaptor.capture(), eq(ACTOR));
        assertThat(((Map<?, ?>) resavedCaptor.getValue().getResultMetadata().get("enhancementOptions"))
                .containsKey("promptFormat")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "compact", ""})
    void invalidSavedPreferenceDoesNotGetUpgradedFromTheResult(String invalid) {
        var saved = recordEntity();
        var options = new java.util.LinkedHashMap<String, Object>(Map.of("templateCode", "FEATURE_DEVELOPMENT"));
        options.put("promptFormat", invalid);
        saved.getResultMetadata().put("enhancementOptions", options);
        saved.getResultMetadata().put("promptFormat", "STRUCTURED");
        when(recordMapper.selectByIdAndScope(saved.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(saved);
        when(orchestrator.optimize(any())).thenReturn(resultWithFileContent());
        service.reoptimize(saved.getId());
        var requestCaptor = ArgumentCaptor.forClass(OptimizationRequest.class);
        verify(orchestrator).optimize(requestCaptor.capture());
        assertThat(requestCaptor.getValue().enhancement().promptFormat()).isNull();
    }

    @Test
    void treatsMissingOrOutOfScopeRecordAsNotFound() {
        UUID unavailableId = UUID.randomUUID();
        when(recordMapper.selectByIdAndScope(unavailableId, TENANT_ID, WORKSPACE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.get(unavailableId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("优化记录不存在或无权访问");
        verify(recordMapper).selectByIdAndScope(unavailableId, TENANT_ID, WORKSPACE_ID);
    }

    @Test
    void pagesHistoryWithEscapedKeywordAndWorkspaceFilters() {
        OptimizationRecordEntity record = recordEntity();
        var projected = new java.util.LinkedHashMap<>(record.getResultMetadata());
        projected.put("generationPipeline", null);
        projected.put("chainVersion", null);
        record.setResultMetadata(projected);
        Page<OptimizationRecordEntity> returned = new Page<>(1, 10);
        returned.setRecords(List.of(record));
        returned.setTotal(1);
        when(recordMapper.selectPageByScope(any(), eq(TENANT_ID), eq(WORKSPACE_ID), eq("登录\\%\\_"),
                isNull(), isNull())).thenReturn(returned);

        OptimizationHistoryPage result = service.list(1, 10, "登录%_", null, null);

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().generationPipeline()).isEqualTo("LEGACY");
        assertThat(result.records().getFirst().chainVersion()).isEqualTo("1");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.current()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        ArgumentCaptor<Page<OptimizationRecordEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(recordMapper).selectPageByScope(pageCaptor.capture(), eq(TENANT_ID), eq(WORKSPACE_ID),
                eq("登录\\%\\_"), isNull(), isNull());
        assertThat(pageCaptor.getValue().optimizeCountSql()).isFalse();
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
        verify(recordMapper, never()).selectByIdAndScope(any(), any(), any());
    }

    @Test
    void performsScopedLogicalDeleteAndHidesUnavailableRows() {
        UUID recordId = UUID.randomUUID();
        when(recordMapper.markDeletedByIdAndScope(eq(recordId), eq(TENANT_ID), eq(WORKSPACE_ID),
                any(OffsetDateTime.class))).thenReturn(1);

        service.delete(recordId);

        verify(recordMapper).markDeletedByIdAndScope(eq(recordId), eq(TENANT_ID), eq(WORKSPACE_ID),
                any(OffsetDateTime.class));
        when(recordMapper.markDeletedByIdAndScope(eq(recordId), eq(TENANT_ID), eq(WORKSPACE_ID),
                any(OffsetDateTime.class))).thenReturn(0);
        assertThatThrownBy(() -> service.delete(recordId)).isInstanceOf(ResourceNotFoundException.class);
    }

    private OptimizationRequest request() {
        return new OptimizationRequest("给用户模块添加登录功能",
                new ContextAnalysisRequest("Spring Boot 用户服务", List.of()),
                EnhancementOptions.defaults(), List.of(), PermissionPolicyInput.empty());
    }

    @Test
    void naturalHistoryKeepsItsOriginalBodyAndRejectsV1EditingProtocol() {
        var record = naturalRecord();
        when(recordMapper.selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(record);
        assertThat(service.getDocument(record.getId()).resultKind()).isEqualTo("TEXT");
        assertThat(service.getDocument(record.getId()).sections()).isEmpty();
        assertThat(service.getDocument(record.getId()).optimizedPrompt()).isEqualTo(record.getOptimizedPrompt());
        assertThatThrownBy(() -> service.get(record.getId()))
                .isInstanceOf(com.promptoptimizer.common.exception.InvalidOptimizationRequestException.class);
    }

    @Test
    void processingFactsSurviveSaveAndDetailWithoutInventingThemForOldHistory() {
        var processing = new com.promptoptimizer.enhancement.domain.NaturalProcessing(
                List.of(com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.SCOPE_ANALYSIS,
                        com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.BODY_GENERATION), false);
        var document = new PromptDocument("生成正文", PromptDocument.ResultKind.TEXT, null,
                resultWithFileContent().contextReport(), List.of(), List.of(), TemplateCode.GENERAL,
                new ProviderMetadata("fixture", "fixture", false), 1, List.of(), List.of(), PromptFormat.COMPACT,
                null, "NATURAL", "39", "APE", null, 0).withProcessing(processing);
        service.saveDocument(request(), document.withRecordId(UUID.randomUUID()));
        var captured = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(persistenceService).persist(captured.capture(), eq(ACTOR));
        var saved = captured.getValue();
        when(recordMapper.selectByIdAndScope(saved.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(saved);
        assertThat(service.getDocument(saved.getId()).processing()).isEqualTo(processing);
        saved.getResultMetadata().remove("processing");
        assertThat(service.getDocument(saved.getId()).processing()).isNull();
        assertThat(saved.getOptimizedPrompt()).isEqualTo("生成正文");
    }

    @Test
    void naturalTextEditPreservesGenerationSnapshotAndUsesScopedVersionedWrite() {
        var record = naturalRecord();
        when(recordMapper.selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(record);
        when(recordMapper.updateNaturalText(eq(record.getId()), eq(TENANT_ID), eq(WORKSPACE_ID), eq(0L), any(), any())).thenReturn(1);
        var result = service.editText(record.getId(), new com.promptoptimizer.history.dto.PromptTextEditRequest("新正文。事实边界。", 0));
        assertThat(result.editRevision()).isEqualTo(1);
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(recordMapper).updateNaturalText(eq(record.getId()), eq(TENANT_ID), eq(WORKSPACE_ID),
                eq(0L), eq("新正文。事实边界。"), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("generatedPrompt", record.getOptimizedPrompt());
        assertThat(metadata.getValue()).containsEntry("editRevision", 1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void naturalHistoryPreservesSavedBodyButAllowsEditingOutOldFixedClauses(boolean hasOldFixedClauses) {
        var groups = new ConstraintBundle(hasOldFixedClauses ? PlatformConstraintRules.CODE_RULES : List.of(), List.of(),
                List.of("禁止读取或输出受保护路径：private/**。"));
        String boundaries = String.join("\n", groups.visibleConstraints());
        String body = "保留原定任务正文。\n\n" + boundaries;
        var document = new PromptDocument(body, PromptDocument.ResultKind.TEXT, null, resultWithFileContent().contextReport(),
                List.of(), groups.visibleConstraints(), TemplateCode.GENERAL, new ProviderMetadata("mock", "fixture", true),
                0, List.of(), List.of(), PromptFormat.COMPACT, groups, PromptDocument.NATURAL_PIPELINE, "3", "APE", null, 0);
        UUID id = service.saveDocument(request(), document);
        var saved = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(persistenceService).persist(saved.capture(), eq(ACTOR));
        var record = saved.getValue();
        assertThat(record.getOptimizedPrompt()).isEqualTo(body);
        assertThat(objectMapper.convertValue(record.getResultMetadata().get("constraintGroups"), ConstraintBundle.class))
                .isEqualTo(groups);
        when(recordMapper.selectByIdAndScope(id, TENANT_ID, WORKSPACE_ID)).thenReturn(record);
        assertThat(service.getDocument(id).optimizedPrompt()).isEqualTo(body);
        assertThat(service.getDocument(id).constraintGroups()).isEqualTo(groups);

        assertThatThrownBy(() -> service.editText(id,
                new com.promptoptimizer.history.dto.PromptTextEditRequest("修改后的任务正文。", 0)))
                .isInstanceOf(com.promptoptimizer.common.exception.InvalidOptimizationRequestException.class);
        verify(recordMapper, never()).updateNaturalText(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        String edited = "修改后的任务正文。\n\n" + String.join("\n", groups.additionalPermissions());
        when(recordMapper.updateNaturalText(eq(id), eq(TENANT_ID), eq(WORKSPACE_ID), eq(0L), eq(edited), any())).thenReturn(1);
        assertThat(service.editText(id, new com.promptoptimizer.history.dto.PromptTextEditRequest(edited, 0)).optimizedPrompt())
                .isEqualTo(edited);
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(recordMapper).updateNaturalText(eq(id), eq(TENANT_ID), eq(WORKSPACE_ID), eq(0L), eq(edited), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("generatedPrompt", body);
        var updatedGroups = objectMapper.convertValue(metadata.getValue().get("constraintGroups"), ConstraintBundle.class);
        assertThat(updatedGroups.platformMandatory()).isEmpty();
        assertThat(updatedGroups.additionalPermissions()).isEqualTo(groups.additionalPermissions());
        assertThat(metadata.getValue().get("appliedConstraints")).isEqualTo(groups.additionalPermissions());
    }

    @Test
    void naturalHistoryWithoutPermissionRulesCanSaveEditedBodyWithoutRestoringFixedClauses() {
        var record = naturalRecord();
        record.getResultMetadata().put("constraintGroups", Map.of("platformMandatory", List.of(),
                "taskSpecific", List.of(), "additionalPermissions", List.of()));
        when(recordMapper.selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(record);
        when(recordMapper.updateNaturalText(eq(record.getId()), eq(TENANT_ID), eq(WORKSPACE_ID), eq(0L), any(), any())).thenReturn(1);
        assertThat(service.editText(record.getId(), new com.promptoptimizer.history.dto.PromptTextEditRequest("修改后的任务正文。", 0))
                .optimizedPrompt()).isEqualTo("修改后的任务正文。");
    }

    @Test
    void naturalEditCannotDropExplicitPermissionsOrOverwriteDeletedOrNewerRecords() {
        var record = naturalRecord();
        record.getResultMetadata().put("constraintGroups", Map.of("platformMandatory", List.of("事实边界。"),
                "taskSpecific", List.of(), "additionalPermissions", List.of("删除前确认。")));
        when(recordMapper.selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(record);
        assertThatThrownBy(() -> service.editText(record.getId(),
                new com.promptoptimizer.history.dto.PromptTextEditRequest("删除了边界", 0)))
                .isInstanceOf(com.promptoptimizer.common.exception.InvalidOptimizationRequestException.class);
        verify(recordMapper, never()).updateNaturalText(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        assertThatThrownBy(() -> service.editText(record.getId(),
                new com.promptoptimizer.history.dto.PromptTextEditRequest("新正文。删除前确认。", 0)))
                .isInstanceOf(com.promptoptimizer.common.exception.InvalidOptimizationRequestException.class);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"1", "2", "3", "4", "31", "core-repair-v1", "core-repair-v2"})
    void naturalReoptimizationUsesSavedPipelineAndVersionInsteadOfLegacyOrchestrator(String version) {
        var record = naturalRecord();
        record.getResultMetadata().put("chainVersion", version);
        when(recordMapper.selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(record);
        var natural = mock(com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService.class);
        var result = new com.promptoptimizer.enhancement.domain.PromptDocument(record.getOptimizedPrompt(),
                com.promptoptimizer.enhancement.domain.PromptDocument.ResultKind.TEXT, null, resultWithFileContent().contextReport(),
                List.of(), List.of("事实边界。"), TemplateCode.GENERAL, new ProviderMetadata("mock", "fixture", true), 0,
                List.of(), List.of(), com.promptoptimizer.enhancement.domain.PromptFormat.COMPACT, null, "NATURAL", version, "APE", null, 0);
        when(natural.optimize(any(), eq(version))).thenReturn(result);
        service.setNaturalEnhancement(natural);
        assertThat(service.reoptimizeDocument(record.getId()).result().resultKind())
                .isEqualTo(com.promptoptimizer.enhancement.domain.PromptDocument.ResultKind.TEXT);
        verify(natural).optimize(any(), eq(version));
        verify(orchestrator, never()).optimize(any());
    }

    private OptimizationRecordEntity naturalRecord() {
        var entity = recordEntity();
        entity.setOptimizedPrompt("自然正文。事实边界。");
        var metadata = new java.util.LinkedHashMap<>(entity.getResultMetadata());
        metadata.remove("sections");
        metadata.put("resultKind", "TEXT");
        metadata.put("generationPipeline", "NATURAL");
        metadata.put("chainVersion", "1");
        metadata.put("promptFormat", "COMPACT");
        metadata.put("constraintGroups", Map.of("platformMandatory", List.of("事实边界。"),
                "taskSpecific", List.of(), "additionalPermissions", List.of()));
        entity.setResultMetadata(metadata);
        return entity;
    }

    @Test
    void unknownGenerationVersionIsNotSilentlySentToEitherGenerator() {
        var record = naturalRecord();
        record.getResultMetadata().put("planConfirmation", Map.of("futureSchema", "不可按旧DTO解析"));
        when(recordMapper.selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(record);
        for (Object version : List.of("unreleased", 1)) {
            record.getResultMetadata().put("chainVersion", version);
            assertThatThrownBy(() -> service.reoptimizeDocument(record.getId()))
                    .isInstanceOf(com.promptoptimizer.common.exception.UnsupportedResultVersionException.class);
        }
        record.getResultMetadata().put("chainVersion", "unreleased");
        record.getResultMetadata().put("resultKind", "SECTIONS");
        record.getResultMetadata().put("generationPipeline", "LEGACY");
        assertThatThrownBy(() -> service.reoptimizeDocument(record.getId()))
                .isInstanceOf(com.promptoptimizer.common.exception.UnsupportedResultVersionException.class);
        verify(orchestrator, never()).optimize(any());
        verify(persistenceService, never()).persist(any(), any());
    }

    @Test
    void naturalHistoryStoresCanonicalServerQuestionInsteadOfClientQuestion() {
        var original = request();
        var confirmation = new com.promptoptimizer.enhancement.dto.PlanConfirmation("11111111-1111-4111-8111-111111111111", null,
                List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer("q1", "伪造的授权问题", "仅限指定范围")));
        var input = new OptimizationRequest(original.rawPrompt(), original.context(), original.enhancement(),
                original.conversationHistory(), original.permissionPolicy(), confirmation, null);
        var canonical = List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer("q1", "真实范围问题", "仅限指定范围"));
        var document = new com.promptoptimizer.enhancement.domain.PromptDocument("自然正文",
                com.promptoptimizer.enhancement.domain.PromptDocument.ResultKind.TEXT, null, resultWithFileContent().contextReport(),
                List.of(), List.of(), TemplateCode.GENERAL, new ProviderMetadata("mock", "fixture", true), 0,
                List.of(), List.of(), com.promptoptimizer.enhancement.domain.PromptFormat.COMPACT, null,
                "NATURAL", "1", "APE", null, 0, canonical);
        service.saveDocument(input, document);
        var saved = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(persistenceService).persist(saved.capture(), eq(ACTOR));
        var stored = objectMapper.convertValue(saved.getValue().getResultMetadata().get("planConfirmation"),
                com.promptoptimizer.enhancement.dto.PlanConfirmation.class);
        assertThat(stored.answers()).isEqualTo(canonical);
    }

    private OptimizationResult resultWithFileContent() {
        return new OptimizationResult(
                "## 任务目标\n实现登录功能",
                List.of(new PromptSection(PromptSectionType.TASK, "任务目标", "实现登录功能")),
                new ContextSnapshot("Spring Boot 用户服务", List.of(), List.of(), List.of("src/App.java"),
                        List.of(), List.of(), List.of(), "v1"),
                List.of(), List.of(), TemplateCode.FEATURE_DEVELOPMENT,
                new ProviderMetadata("mock", "deterministic-enhancer-v1", true), 42);
    }

    private OptimizationRecordEntity recordEntity() {
        OptimizationRecordEntity entity = new OptimizationRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT_ID);
        entity.setWorkspaceId(WORKSPACE_ID);
        entity.setCreatedBy(USER_ID);
        entity.setTemplateCode("FEATURE_DEVELOPMENT");
        entity.setRawPrompt("给用户模块添加登录功能");
        entity.setOptimizedPrompt("## 任务目标\n实现登录功能");
        entity.setContextSnapshot(Map.of("customDescription", "Spring Boot 用户服务"));
        entity.setResultMetadata(Map.of(
                "sections", List.of(Map.of("type", "TASK", "title", "任务目标", "content", "实现登录功能")),
                "ambiguities", List.of(), "appliedConstraints", List.of(), "provider", "mock",
                "model", "deterministic-enhancer-v1", "mock", true,
                "enhancementOptions", Map.of("templateCode", "FEATURE_DEVELOPMENT", "includeConversationHistory", false,
                        "includePermissionBoundaries", false, "includeExamples", false),
                "conversationHistory", List.of()));
        entity.setPermissionPolicy(Map.of());
        entity.setLatencyMs(12);
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return entity;
    }
}
