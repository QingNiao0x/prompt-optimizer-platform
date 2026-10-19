package com.promptoptimizer.enhancement.dto;

import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.controller.DocumentOptimizationController;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.service.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.history.service.OptimizationHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** V2协议在真实安全链下验证模式分发、原生正文和Plan完成计数时机。 */
@WebMvcTest(DocumentOptimizationController.class)
@Import({RequestIdFilter.class, com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration.class})
class DocumentOptimizationControllerTest {
    @Autowired MockMvc mvc;
    @MockBean NaturalEnhancementService natural;
    @MockBean EnhancementOrchestrator structured;
    @MockBean OptimizationHistoryService history;
    @MockBean AnalyticsEventService analytics;

    @Test
    void defaultsToNaturalAndReturnsActualSavedIdWithoutSections() throws Exception {
        var id = new UUID(0, 5);
        when(natural.optimize(any())).thenReturn(document());
        when(history.saveDocument(any(), any())).thenReturn(id);
        mvc.perform(post("/api/v2/optimizations").contentType(APPLICATION_JSON).content("{\"rawPrompt\":\"写邮件\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultKind").value("TEXT"))
                .andExpect(jsonPath("$.data.sections").doesNotExist())
                .andExpect(jsonPath("$.data.recordId").value(id.toString()));
        verifyNoInteractions(structured);
    }
    @Test
    void onlyExplicitStructuredPreferenceUsesLegacyGenerator() throws Exception {
        when(structured.optimize(any())).thenReturn(new OptimizationResult("结构正文",
                List.of(new PromptSection(PromptSectionType.TASK, "任务", "任务内容")), context(), List.of(), List.of(),
                TemplateCode.GENERAL, new ProviderMetadata("mock", "old", true), 0));
        mvc.perform(post("/api/v2/optimizations").contentType(APPLICATION_JSON)
                        .content("{\"rawPrompt\":\"写邮件\",\"enhancement\":{\"promptFormat\":\"STRUCTURED\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultKind").value("SECTIONS"))
                .andExpect(jsonPath("$.data.sections.length()").value(1))
                .andExpect(jsonPath("$.data.sections[0].type").value("TASK"))
                .andExpect(jsonPath("$.data.optimizedPrompt").value("## 任务\n任务内容"));
        verifyNoInteractions(natural);
    }
    @Test
    void countsPlanCompletionOnlyAfterHistorySave() throws Exception {
        when(natural.optimize(any())).thenReturn(document());
        String plan = "11111111-1111-4111-8111-111111111111";
        mvc.perform(post("/api/v2/optimizations").contentType(APPLICATION_JSON)
                        .content("{\"rawPrompt\":\"写邮件\",\"planConfirmation\":{\"planId\":\"" + plan + "\",\"answers\":[]}}"))
                .andExpect(status().isOk());
        var order = inOrder(natural, history, analytics);
        order.verify(analytics).recordOptimizationSubmission(eq(false), any());
        order.verify(natural).optimize(any());
        order.verify(history).saveDocument(any(), any());
        order.verify(analytics).recordPlanCompleted(eq(plan), any());
    }
    private ContextSnapshot context() { return new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"); }
    private PromptDocument document() {
        return new PromptDocument("自然正文", PromptDocument.ResultKind.TEXT, null, context(), List.of(), List.of(),
                TemplateCode.GENERAL, new ProviderMetadata("mock", "new", true), 0, List.of(), List.of(), PromptFormat.COMPACT,
                null, "NATURAL", "1", "APE", null, 0);
    }
}
