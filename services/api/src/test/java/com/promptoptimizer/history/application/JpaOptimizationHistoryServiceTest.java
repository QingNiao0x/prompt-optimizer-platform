package com.promptoptimizer.history.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.promptoptimizer.context.api.ContextAnalysisRequest;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.api.EnhancementOptions;
import com.promptoptimizer.enhancement.api.OptimizationRequest;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.api.PermissionPolicyInput;
import com.promptoptimizer.enhancement.application.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.history.domain.OptimizationHistoryDetail;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.domain.ReoptimizationResult;
import com.promptoptimizer.history.infrastructure.OptimizationRecordEntity;
import com.promptoptimizer.history.infrastructure.OptimizationRecordRepository;
import com.promptoptimizer.history.infrastructure.OptimizationSessionEntity;
import com.promptoptimizer.history.infrastructure.OptimizationSessionRepository;
import com.promptoptimizer.identity.application.ActorIdentity;
import com.promptoptimizer.identity.application.CurrentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 历史服务单元测试，验证脱敏保存、会话复用、详情重建和重新优化。
 */
class JpaOptimizationHistoryServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final OptimizationRecordRepository recordRepository = mock(OptimizationRecordRepository.class);
    private final OptimizationSessionRepository sessionRepository = mock(OptimizationSessionRepository.class);
    private final EnhancementOrchestrator orchestrator = mock(EnhancementOrchestrator.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private JpaOptimizationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new JpaOptimizationHistoryService(
                recordRepository,
                sessionRepository,
                orchestrator,
                currentActor,
                objectMapper
        );
        when(currentActor.require()).thenReturn(new ActorIdentity(
                USER_ID,
                TENANT_ID,
                WORKSPACE_ID,
                "demo@local",
                "Local Demo User"
        ));
        when(sessionRepository.save(any(OptimizationSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(recordRepository.save(any(OptimizationRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldSaveRecordWithoutPersistingFileContent() {
        when(sessionRepository.findFirstByTenantIdAndWorkspaceIdAndStatusOrderByCreatedAtAsc(
                eq(TENANT_ID), eq(WORKSPACE_ID), eq("ACTIVE")
        )).thenReturn(Optional.empty());

        UUID savedId = service.save(request(), resultWithFileContent());

        ArgumentCaptor<OptimizationRecordEntity> captor = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(recordRepository).save(captor.capture());

        OptimizationRecordEntity saved = captor.getValue();
        assertThat(savedId).isEqualTo(saved.getId());
        assertThat(saved.getContextSnapshot()).containsKey("customDescription");
        assertThat(saved.getContextSnapshot()).doesNotContainKey("fileSnippets");
        assertThat(saved.getResultMetadata()).containsKeys(
                "sections", "ambiguities", "appliedConstraints", "enhancementOptions"
        );
        assertThat(saved.getTemplateCode()).isEqualTo("FEATURE_DEVELOPMENT");
        assertThat(saved.getLatencyMs()).isEqualTo(42);
    }

    @Test
    void shouldReuseExistingDefaultSessionWhenPresent() {
        OptimizationSessionEntity session = sessionEntity();
        when(sessionRepository.findFirstByTenantIdAndWorkspaceIdAndStatusOrderByCreatedAtAsc(
                eq(TENANT_ID), eq(WORKSPACE_ID), eq("ACTIVE")
        )).thenReturn(Optional.of(session));

        service.save(request(), resultWithFileContent());

        ArgumentCaptor<OptimizationRecordEntity> captor = ArgumentCaptor.forClass(OptimizationRecordEntity.class);
        verify(recordRepository).save(captor.capture());
        assertThat(captor.getValue().getSessionId()).isEqualTo(session.getId());
    }

    @Test
    void shouldRebuildDetailFromMetadata() {
        OptimizationRecordEntity entity = recordEntity();
        when(recordRepository.findByIdAndTenantIdAndWorkspaceId(entity.getId(), TENANT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(entity));

        OptimizationHistoryDetail detail = service.get(entity.getId());

        assertThat(detail.rawPrompt()).isEqualTo("给用户模块添加登录功能");
        assertThat(detail.sections()).hasSize(1);
        assertThat(detail.sections().get(0).type()).isEqualTo(PromptSectionType.TASK);
        assertThat(detail.includePermissionBoundaries()).isTrue();
        assertThat(detail.includeExamples()).isFalse();
        assertThat(detail.conversationHistory()).hasSize(1);
    }

    @Test
    void shouldReturnPagedSummaries() {
        OptimizationRecordEntity entity = recordEntity();
        Page<OptimizationRecordEntity> page = new PageImpl<>(
                List.of(entity),
                PageRequest.of(0, 20),
                1
        );
        when(recordRepository.findByTenantIdAndWorkspaceIdOrderByCreatedAtDesc(
                eq(TENANT_ID), eq(WORKSPACE_ID), any()
        )).thenReturn(page);

        OptimizationHistoryPage result = service.list(0, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.items().get(0).rawPromptPreview()).contains("添加登录功能");
    }

    @Test
    void shouldReoptimizeAndSaveNewRecord() {
        OptimizationRecordEntity source = recordEntity();
        OptimizationResult newResult = resultWithoutFiles();
        when(recordRepository.findByIdAndTenantIdAndWorkspaceId(source.getId(), TENANT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(source));
        when(orchestrator.optimize(any())).thenReturn(newResult);
        when(sessionRepository.findFirstByTenantIdAndWorkspaceIdAndStatusOrderByCreatedAtAsc(
                eq(TENANT_ID), eq(WORKSPACE_ID), eq("ACTIVE")
        )).thenReturn(Optional.of(sessionEntity()));

        ReoptimizationResult result = service.reoptimize(source.getId());

        verify(orchestrator).optimize(any());
        assertThat(result.result()).isSameAs(newResult);
        assertThat(result.recordId()).isNotNull();
    }

    @Test
    void shouldReplayConfirmedPlanAnswersWhenReoptimizingARecord() {
        OptimizationRecordEntity source = recordEntityWithPlan();
        when(recordRepository.findByIdAndTenantIdAndWorkspaceId(source.getId(), TENANT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(source));
        when(orchestrator.optimize(any())).thenReturn(resultWithoutFiles());
        when(sessionRepository.findFirstByTenantIdAndWorkspaceIdAndStatusOrderByCreatedAtAsc(
                eq(TENANT_ID), eq(WORKSPACE_ID), eq("ACTIVE")
        )).thenReturn(Optional.of(sessionEntity()));

        service.reoptimize(source.getId());

        ArgumentCaptor<OptimizationRequest> requestCaptor = ArgumentCaptor.forClass(OptimizationRequest.class);
        verify(orchestrator).optimize(requestCaptor.capture());
        assertThat(requestCaptor.getValue().planConfirmation()).isNull();
        assertThat(requestCaptor.getValue().rawPrompt())
                .contains("用户此前已确认的信息", "研究覆盖哪个地区？：广东省");
    }

    @Test
    void shouldDeleteRecordInCurrentWorkspace() {
        OptimizationRecordEntity entity = recordEntity();
        when(recordRepository.findByIdAndTenantIdAndWorkspaceId(entity.getId(), TENANT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(entity));

        service.delete(entity.getId());

        verify(recordRepository).delete(entity);
    }

    private OptimizationRequest request() {
        return new OptimizationRequest(
                "给用户模块添加登录功能",
                new ContextAnalysisRequest("Spring Boot 用户服务", List.of()),
                EnhancementOptions.defaults(),
                List.of(),
                PermissionPolicyInput.empty()
        );
    }

    private OptimizationResult resultWithFileContent() {
        return new OptimizationResult(
                "## 任务目标\n实现登录功能",
                List.of(new PromptSection(PromptSectionType.TASK, "任务目标", "实现登录功能")),
                new ContextSnapshot(
                        "Spring Boot 用户服务",
                        List.of(),
                        List.of(),
                        List.of("src/main/java/UserController.java"),
                        List.of(new FileSnippet(
                                "src/main/java/UserController.java",
                                "java",
                                "class App {}",
                                "Java 源码，定义 App 类。",
                                false
                        )),
                        List.of(),
                        List.of(),
                        "v1"
                ),
                List.of("登录方式未明确"),
                List.of("处理空输入"),
                TemplateCode.FEATURE_DEVELOPMENT,
                new ProviderMetadata("mock", "deterministic-enhancer-v1", true),
                42
        );
    }

    private OptimizationResult resultWithoutFiles() {
        return new OptimizationResult(
                "## 任务目标\n实现登录功能",
                List.of(new PromptSection(PromptSectionType.TASK, "任务目标", "实现登录功能")),
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                List.of(),
                List.of(),
                TemplateCode.FEATURE_DEVELOPMENT,
                new ProviderMetadata("mock", "deterministic-enhancer-v1", true),
                5
        );
    }

    private OptimizationSessionEntity sessionEntity() {
        OptimizationSessionEntity session = new OptimizationSessionEntity();
        session.setId(UUID.randomUUID());
        session.setTenantId(TENANT_ID);
        session.setWorkspaceId(WORKSPACE_ID);
        session.setCreatedBy(USER_ID);
        session.setTitle("默认优化会话");
        session.setStatus("ACTIVE");
        return session;
    }

    private OptimizationRecordEntity recordEntity() {
        OptimizationRecordEntity entity = new OptimizationRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT_ID);
        entity.setWorkspaceId(WORKSPACE_ID);
        entity.setSessionId(sessionEntity().getId());
        entity.setCreatedBy(USER_ID);
        entity.setTemplateCode("FEATURE_DEVELOPMENT");
        entity.setRawPrompt("给用户模块添加登录功能");
        entity.setOptimizedPrompt("## 任务目标\n实现登录功能");
        entity.setContextSnapshot(Map.of("customDescription", "Spring Boot 用户服务"));
        entity.setResultMetadata(Map.of(
                "sections", List.of(Map.of(
                        "type", "TASK",
                        "title", "任务目标",
                        "content", "实现登录功能"
                )),
                "ambiguities", List.of("登录方式未明确"),
                "appliedConstraints", List.of("处理空输入"),
                "provider", "mock",
                "model", "deterministic-enhancer-v1",
                "mock", true,
                "enhancementOptions", Map.of(
                        "templateCode", "FEATURE_DEVELOPMENT",
                        "includeConversationHistory", true,
                        "includePermissionBoundaries", true,
                        "includeExamples", false
                ),
                "conversationHistory", List.of(Map.of(
                        "role", "user",
                        "content", "请保持接口兼容"
                ))
        ));
        entity.setPermissionPolicy(Map.of(
                "protectedPaths", List.of(),
                "requireConfirmationFor", List.of()
        ));
        entity.setLatencyMs(12);
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return entity;
    }

    private OptimizationRecordEntity recordEntityWithPlan() {
        OptimizationRecordEntity entity = recordEntity();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(entity.getResultMetadata());
        metadata.put("planConfirmation", Map.of(
                "answers", List.of(Map.of(
                        "questionId", "research-region",
                        "question", "研究覆盖哪个地区？",
                        "answer", "广东省"
                ))
        ));
        entity.setResultMetadata(metadata);
        return entity;
    }
}
