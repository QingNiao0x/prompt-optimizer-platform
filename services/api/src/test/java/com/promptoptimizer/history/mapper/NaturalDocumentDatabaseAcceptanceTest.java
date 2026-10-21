package com.promptoptimizer.history.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.*;
import com.promptoptimizer.history.domain.PromptTextEditResult;
import com.promptoptimizer.history.dto.PromptTextEditRequest;
import com.promptoptimizer.history.service.OptimizationHistoryService;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * 使用既有合成账号与真实本机PostgreSQL验证V2 JSON、分页、CAS及逻辑删除。
 * 不创建账号、租户或工作区；记录与注释验证全部在事务内回滚，不连接真实模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@SpringBootTest(properties = {
        "app.provider.mode=mock", "spring.flyway.enabled=false",
        "app.security.bootstrap-admin.enabled=false", "app.security.bootstrap-user.password=",
        "app.history.enabled=true", "app.provider.concurrency.store-mode=MEMORY"
})
@Transactional
@AutoConfigureMockMvc
class NaturalDocumentDatabaseAcceptanceTest {
    @Autowired OptimizationHistoryService history;
    @Autowired OptimizationRecordMapper records;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @MockBean CurrentActor currentActor;
    @MockBean com.promptoptimizer.provider.infrastructure.PlatformModelCatalogService modelCatalog;
    @MockBean com.promptoptimizer.analytics.infrastructure.AuditEventDelivery auditDelivery;
    @MockBean com.promptoptimizer.analytics.service.AnalyticsEventService analytics;
    @Autowired MockMvc mvc;
    private ActorIdentity actor;
    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void useCanonicalExistingAccount() throws Exception {
        Path reference = Path.of("../../tmp/local-acceptance/account.json").normalize();
        if (!Files.isRegularFile(reference)) throw new IllegalStateException("缺少固定合成账号引用，禁止创建替代账号");
        var account = json.readTree(Files.readString(reference));
        actor = new ActorIdentity(UUID.fromString(account.path("userId").asText()),
                UUID.fromString(account.path("tenantId").asText()), UUID.fromString(account.path("workspaceId").asText()),
                "acceptance@local", "Canonical acceptance");
        when(currentActor.require()).thenReturn(actor);
        when(modelCatalog.resolve(any())).thenReturn(new com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry(
                new UUID(0, 4), "mock:fixture", "mock", "fixture", "离线验收模型", true, true, 0));
    }

    @Test
    void realPostgresKeepsNaturalTextVersionScopeDeletionAndLegacyPagination() throws Exception {
        // COMMENT也在当前事务内验证并回滚，既有Flyway历史不被修改。
        ScriptUtils.executeSqlScript(DataSourceUtils.getConnection(dataSource),
                new ClassPathResource("db/migration/V16__natural_prompt_document_metadata.sql"));
        ScriptUtils.executeSqlScript(DataSourceUtils.getConnection(dataSource),
                new ClassPathResource("db/migration/V17__natural_task_strategy_version_comment.sql"));
        String marker = "natural-acceptance-" + UUID.randomUUID();
        var input = new OptimizationRequest(marker, new com.promptoptimizer.context.dto.ContextAnalysisRequest("", List.of()),
                new EnhancementOptions(TemplateCode.AUTO, false, true, PromptFormat.COMPACT),
                List.of(), PermissionPolicyInput.empty(), null, null);
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        var groups = new ConstraintBundle(List.of("事实边界。"), List.of(), List.of());
        var document = new PromptDocument("原始自然正文。事实边界。", PromptDocument.ResultKind.TEXT, null,
                context, List.of(), groups.visibleConstraints(), TemplateCode.GENERAL,
                new ProviderMetadata("mock", "fixture", true), 1, List.of(), List.of(), PromptFormat.COMPACT,
                groups, "NATURAL", "1", "APE", null, 0);
        UUID natural = history.saveDocument(input, document);
        created.add(natural);
        UUID legacy = history.save(input, new OptimizationResult("旧正文。",
                List.of(new PromptSection(PromptSectionType.TASK, "任务", "旧正文。")), context, List.of(), List.of(),
                TemplateCode.GENERAL, new ProviderMetadata("mock", "fixture", true), 1));
        created.add(legacy);
        assertThat(history.list(1, 10, marker, null, null).records()).extracting(r -> r.id()).containsExactly(legacy);
        assertThat(history.listAll(1, 10, marker, null, null).records()).extracting(r -> r.id()).containsExactlyInAnyOrder(natural, legacy);
        assertThat(history.getDocument(natural).sections()).isEmpty();
        assertThat(history.getDocument(natural).optimizedPrompt()).isEqualTo(document.optimizedPrompt());
        var reoptimized = history.reoptimizeDocument(natural);
        created.add(reoptimized.recordId());
        assertThat(reoptimized.result().chainVersion()).isEqualTo("1");
        history.delete(reoptimized.recordId());

        PromptTextEditResult edited = history.editText(natural, new PromptTextEditRequest("有效修改。事实边界。", 0));
        assertThat(edited.editRevision()).isEqualTo(1);
        assertThat(history.getDocument(natural).optimizedPrompt()).isEqualTo(edited.optimizedPrompt());
        var saved = records.selectByIdAndScope(natural, actor.tenantId(), actor.workspaceId());
        assertThat(saved.getResultMetadata()).containsEntry("generatedPrompt", document.optimizedPrompt());
        assertThat(saved.getResultMetadata()).doesNotContainKey("sections");
        assertThat(records.updateNaturalText(natural, actor.tenantId(), actor.workspaceId(), 0,
                "过期页面。事实边界。", saved.getResultMetadata())).isZero();
        assertThat(records.updateNaturalText(natural, actor.tenantId(), new UUID(0, 99), 1,
                "越界。事实边界。", saved.getResultMetadata())).isZero();
        assertThat(history.getDocument(natural).optimizedPrompt()).isEqualTo("有效修改。事实边界。");
        assertThat(records.markDeletedByIdAndScope(natural, actor.tenantId(), actor.workspaceId(),
                OffsetDateTime.now(ZoneOffset.UTC))).isEqualTo(1);
        assertThat(records.selectByIdAndScope(natural, actor.tenantId(), actor.workspaceId())).isNull();
        assertThat(records.updateNaturalText(natural, actor.tenantId(), actor.workspaceId(), 1,
                "删除后覆盖。事实边界。", saved.getResultMetadata())).isZero();
        assertThat(history.listAll(1, 10, marker, null, null).records()).extracting(r -> r.id()).containsExactly(legacy);

        // 模拟真实V2之前的记录：列表jsonb投影把缺失键变成null，仍须恢复LEGACY/1，而非空版本。
        var preV2 = records.selectByIdAndScope(legacy, actor.tenantId(), actor.workspaceId());
        preV2.setId(UUID.randomUUID());
        var oldMetadata = new LinkedHashMap<>(preV2.getResultMetadata());
        List.of("resultKind", "generationPipeline", "chainVersion", "framework").forEach(oldMetadata::remove);
        preV2.setResultMetadata(oldMetadata);
        records.insertRecord(preV2);
        created.add(preV2.getId());
        var summary = history.listAll(1, 10, marker, null, null).records().stream()
                .filter(row -> row.id().equals(preV2.getId())).findFirst().orElseThrow();
        assertThat(summary.resultKind()).isEqualTo("SECTIONS");
        assertThat(summary.generationPipeline()).isEqualTo("LEGACY");
        assertThat(summary.chainVersion()).isEqualTo("1");
        assertThat(history.get(preV2.getId()).optimizedPrompt()).isEqualTo("旧正文。");
    }

    @Test
    void authenticatedV2PlanGenerationEditHistoryAndStructuredModeUseActualServicesAndDatabase() throws Exception {
        String raw = "为客户写一封简短交付确认邮件，明确交付项及反馈方式，不虚构日期。";
        String planResponse = mvc.perform(post("/api/v2/optimizations/plan").with(user("canonical-acceptance")).with(csrf())
                        .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("rawPrompt", raw))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.questions").isEmpty())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String planId = json.readTree(planResponse).path("data").path("planId").asText();
        String response = mvc.perform(post("/api/v2/optimizations").with(user("canonical-acceptance")).with(csrf())
                        .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("rawPrompt", raw,
                                "planConfirmation", Map.of("planId", planId, "answers", List.of())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultKind").value("TEXT"))
                .andExpect(jsonPath("$.data.sections").doesNotExist())
                .andExpect(jsonPath("$.data.confirmedPlanAnswers").doesNotExist())
                .andExpect(jsonPath("$.data.taskUnderstanding").doesNotExist())
                .andExpect(jsonPath("$.data.chainVersion").value("2"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var result = json.readTree(response).path("data");
        UUID id = UUID.fromString(result.path("recordId").asText());
        created.add(id);
        String original = result.path("optimizedPrompt").asText();
        assertThat(original).contains(raw);
        assertThat(records.selectByIdAndScope(id, actor.tenantId(), actor.workspaceId()).getResultMetadata())
                .doesNotContainKey("sections");
        String edited = original + "\n保持本次人工补充的称呼。";
        mvc.perform(patch("/api/v2/optimization-history/{id}/document", id).with(user("canonical-acceptance")).with(csrf())
                        .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("optimizedPrompt", edited, "expectedRevision", 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.editRevision").value(1));
        mvc.perform(get("/api/v2/optimization-history/{id}", id).with(user("canonical-acceptance")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.optimizedPrompt").value(edited));
        mvc.perform(get("/api/v1/optimization-history/{id}", id).with(user("canonical-acceptance")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RESULT_VERSION_UNSUPPORTED"));
        mvc.perform(get("/api/v2/optimization-history/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v2/optimization-history/{id}/document", id).with(user("canonical-acceptance"))
                        .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("optimizedPrompt", original, "expectedRevision", 1))))
                .andExpect(status().isForbidden());
        assertThat(history.getDocument(id).optimizedPrompt()).isEqualTo(edited);

        String structuredResponse = mvc.perform(post("/api/v2/optimizations").with(user("canonical-acceptance")).with(csrf())
                        .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("rawPrompt", raw,
                                "enhancement", Map.of("promptFormat", "STRUCTURED"),
                                "planConfirmation", Map.of("planId", planId, "answers", List.of())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultKind").value("SECTIONS"))
                .andExpect(jsonPath("$.data.sections.length()").value(5))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var structured = json.readTree(structuredResponse).path("data");
        created.add(UUID.fromString(structured.path("recordId").asText()));
        assertThat(structured.path("optimizedPrompt").asText()).contains("## 背景", "## 任务", "## 输出要求", "## 约束条件", "## 验收标准");
    }

    /** 确认验收事务不留下优化记录；不清理或触碰任何既有用户历史。 */
    @AfterTransaction
    void verifyRollback() {
        if (actor != null) created.forEach(id ->
                assertThat(records.selectByIdAndScope(id, actor.tenantId(), actor.workspaceId())).isNull());
    }
}
