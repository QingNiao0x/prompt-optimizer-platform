package com.promptoptimizer.history.service;

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
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.entity.OptimizationRecordEntity;
import com.promptoptimizer.history.mapper.OptimizationRecordMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        UUID savedId = service.save(request(), resultWithFileContent());

        ArgumentCaptor<OptimizationRecordEntity> captor = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(persistenceService).persist(captor.capture(), eq(ACTOR));
        OptimizationRecordEntity saved = captor.getValue();
        assertThat(savedId).isEqualTo(saved.getId());
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(saved.getContextSnapshot()).doesNotContainKey("fileSnippets");
    }

    @Test
    void readsOnlyRecordInCurrentTenantAndWorkspace() {
        OptimizationRecordEntity record = recordEntity();
        when(recordMapper.selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID)).thenReturn(record);

        assertThat(service.get(record.getId()).rawPrompt()).isEqualTo("给用户模块添加登录功能");
        verify(recordMapper).selectByIdAndScope(record.getId(), TENANT_ID, WORKSPACE_ID);
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
        Page<OptimizationRecordEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(record));
        page.setTotal(1);
        when(recordMapper.selectPageByScope(any(), eq(TENANT_ID), eq(WORKSPACE_ID),
                eq("登录\\%\\_"), any(), any())).thenReturn(page);

        OptimizationHistoryPage result = service.list(0, 20, "登录%_", null, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.totalItems()).isEqualTo(1);
        ArgumentCaptor<Page<OptimizationRecordEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(recordMapper).selectPageByScope(pageCaptor.capture(), eq(TENANT_ID), eq(WORKSPACE_ID),
                eq("登录\\%\\_"), eq(null), eq(null));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().optimizeCountSql()).isFalse();
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
