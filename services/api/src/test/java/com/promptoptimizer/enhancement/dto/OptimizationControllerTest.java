package com.promptoptimizer.enhancement.dto;

import com.promptoptimizer.enhancement.controller.OptimizationController;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.service.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.service.OptimizationPlanningService;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.history.service.OptimizationHistoryService;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OptimizationController.class)
@Import({RequestIdFilter.class, com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration.class})
class OptimizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EnhancementOrchestrator orchestrator;

    @MockBean
    private OptimizationPlanningService planningService;

    @MockBean
    private OptimizationHistoryService optimizationHistoryService;

    @MockBean
    private AnalyticsEventService analyticsEventService;

    @Test
    void shouldReturnStructuredOptimizationResult() throws Exception {
        ContextSnapshot context = new ContextSnapshot(
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "v1"
        );
        when(orchestrator.optimize(any())).thenReturn(new OptimizationResult(
                "## 任务目标\n实现排序功能",
                List.of(new PromptSection(PromptSectionType.TASK, "任务目标", "实现排序功能")),
                context,
                List.of(),
                List.of("处理空列表"),
                TemplateCode.FEATURE_DEVELOPMENT,
                new ProviderMetadata("mock", "deterministic-enhancer-v1", true),
                1
        ));

        mockMvc.perform(post("/api/v1/optimizations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "rawPrompt": "实现排序功能",
                                  "context": {"files": []}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.data.optimizedPrompt").value("## 任务目标\n实现排序功能"))
                .andExpect(jsonPath("$.data.provider.mock").value(true));
        verify(analyticsEventService).recordOptimizationSubmission(eq(true), any());
        verify(analyticsEventService, never()).recordPlanCompleted(any(), any());
    }

    @Test
    void completesPlanOnlyAfterGenerationAndHistorySave() throws Exception {
        when(orchestrator.optimize(any())).thenReturn(result());
        mockMvc.perform(post("/api/v1/optimizations").contentType(APPLICATION_JSON).content(planRequest()))
                .andExpect(status().isOk());
        var order = inOrder(analyticsEventService, orchestrator, optimizationHistoryService);
        order.verify(analyticsEventService).recordOptimizationSubmission(eq(false), any());
        order.verify(orchestrator).optimize(any());
        order.verify(optimizationHistoryService).save(any(), any());
        order.verify(analyticsEventService).recordPlanCompleted(eq("11111111-1111-4111-8111-111111111111"), any());
        verify(analyticsEventService, never()).recordOptimizationSubmission(eq(true), any());
    }

    @Test
    void planGenerationFailureNeverCountsCompletion() throws Exception {
        when(orchestrator.optimize(any())).thenThrow(new ProviderException(ProviderFailureType.TIMEOUT, "unavailable", true));
        mockMvc.perform(post("/api/v1/optimizations").contentType(APPLICATION_JSON).content(planRequest()))
                .andExpect(status().isGatewayTimeout());
        verify(analyticsEventService).recordOptimizationSubmission(eq(false), any());
        verify(analyticsEventService, never()).recordPlanCompleted(any(), any());
        verifyNoInteractions(optimizationHistoryService);
    }

    @Test
    void planHistoryFailureNeverCountsCompletion() throws Exception {
        when(orchestrator.optimize(any())).thenReturn(result());
        when(optimizationHistoryService.save(any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test unavailable"));
        mockMvc.perform(post("/api/v1/optimizations").contentType(APPLICATION_JSON).content(planRequest()))
                .andExpect(status().isInternalServerError());
        verify(analyticsEventService).recordOptimizationSubmission(eq(false), any());
        verify(analyticsEventService, never()).recordPlanCompleted(any(), any());
    }

    /** 合法无文件、零问题确认只模拟 Controller 协议；计划有效性由真实应用服务测试核实。 */
    private String planRequest() {
        return """
                {"rawPrompt":"实现排序功能","context":{"files":[]},
                 "planConfirmation":{"planId":"11111111-1111-4111-8111-111111111111","answers":[]}}
                """;
    }

    /** 返回完整的最小结果，不以空对象伪造一次成功。 */
    private OptimizationResult result() {
        return new OptimizationResult("## 任务\n实现排序功能", List.of(),
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                List.of(), List.of(), TemplateCode.GENERAL, new ProviderMetadata("mock", "test", true), 1);
    }

    @Test
    void shouldReturnBusinessQuestionsBeforeOptimization() throws Exception {
        when(planningService.plan(any())).thenReturn(new OptimizationPlan(
                "还需要确认研究范围。",
                List.of(new PlanQuestion(
                        "research-region",
                        "这项研究具体覆盖哪个地区？",
                        "请填写明确地区。",
                        PlanQuestionType.FREE_TEXT,
                        List.of(),
                        List.of("广东省"),
                        true
                )),
                TemplateCode.RESEARCH_ANALYSIS,
                new ProviderMetadata("mock", "deterministic-planner-v2", true),
                2
        ));

        mockMvc.perform(post("/api/v1/optimizations/plan")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "rawPrompt": "分析2015年至2025年某地区心脑血管疾病死亡率",
                                  "contextDescription": "公共卫生研究"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].question")
                        .value("这项研究具体覆盖哪个地区？"))
                .andExpect(jsonPath("$.data.templateCode").value("RESEARCH_ANALYSIS"));
    }

    @Test
    void shouldRejectBlankPlanningPrompt() throws Exception {
        mockMvc.perform(post("/api/v1/optimizations/plan")
                        .contentType(APPLICATION_JSON)
                        .content("{\"rawPrompt\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void shouldRejectLegacyConfirmationWithoutBoundPlanId() throws Exception {
        mockMvc.perform(post("/api/v1/optimizations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "rawPrompt": "开发订单接口",
                                  "context": {"files": []},
                                  "planConfirmation": {"answers": []}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(analyticsEventService, orchestrator, optimizationHistoryService);
    }

    @Test
    void shouldRejectMoreThanEightPlanAnswers() throws Exception {
        StringBuilder answers = new StringBuilder();
        for (int index = 0; index < 9; index++) {
            if (index > 0) {
                answers.append(',');
            }
            answers.append("{\"questionId\":\"q").append(index)
                    .append("\",\"question\":\"问题\",\"answer\":\"回答\"}");
        }

        mockMvc.perform(post("/api/v1/optimizations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"rawPrompt\":\"分析数据\",\"planConfirmation\":{\"answers\":["
                                + answers + "]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void shouldRejectPromptLongerThanEightThousandCharacters() throws Exception {
        mockMvc.perform(post("/api/v1/optimizations/plan")
                        .contentType(APPLICATION_JSON)
                        .content("{\"rawPrompt\":\"" + "a".repeat(8_001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void shouldRejectMoreThanTwentyConversationMessages() throws Exception {
        String messages = IntStream.range(0, 21)
                .mapToObj(index -> "{\"role\":\"user\",\"content\":\"message-" + index + "\"}")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/api/v1/optimizations/plan")
                        .contentType(APPLICATION_JSON)
                        .content("{\"rawPrompt\":\"分析数据\",\"conversationHistory\":[" + messages + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void shouldRejectPermissionPolicyListsBeyondTheirLimits() throws Exception {
        String protectedPaths = quotedValues("path-", 51);
        String confirmationActions = quotedValues("action-", 51);

        mockMvc.perform(post("/api/v1/optimizations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"rawPrompt\":\"分析数据\",\"permissionPolicy\":{"
                                + "\"protectedPaths\":[" + protectedPaths + "],"
                                + "\"requireConfirmationFor\":[" + confirmationActions + "]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void shouldRejectBlankRawPrompt() throws Exception {
        mockMvc.perform(post("/api/v1/optimizations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"rawPrompt": "", "context": {"files": []}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(analyticsEventService, orchestrator, optimizationHistoryService);
    }

    @Test
    void shouldReturnRetryableErrorWhenProviderIsRateLimited() throws Exception {
        when(orchestrator.optimize(any())).thenThrow(new ProviderException(
                ProviderFailureType.RATE_LIMIT,
                "上游原始错误不应返回给客户端",
                true
        ));

        mockMvc.perform(post("/api/v1/optimizations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "rawPrompt": "实现排序功能",
                                  "context": {"files": []}
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_RATE_LIMITED"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(jsonPath("$.error.message").value("模型服务当前请求繁忙，请稍后重试。"));
        verify(analyticsEventService).recordOptimizationSubmission(eq(true), any());
        verify(analyticsEventService, never()).recordPlanCompleted(any(), any());
    }

    private String quotedValues(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> "\"" + prefix + index + "\"")
                .collect(Collectors.joining(","));
    }
}
