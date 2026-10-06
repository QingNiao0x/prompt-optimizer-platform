package com.promptoptimizer.history.dto;

import com.promptoptimizer.history.controller.OptimizationHistoryController;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import jakarta.servlet.http.HttpServletRequest;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.history.service.OptimizationHistoryService;
import com.promptoptimizer.history.domain.OptimizationHistoryDetail;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.domain.OptimizationHistorySummary;
import com.promptoptimizer.history.domain.ReoptimizationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 历史接口控制器测试，验证分页、详情、删除和重新优化的响应结构。
 */
@WebMvcTest(OptimizationHistoryController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({RequestIdFilter.class, com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration.class})
class OptimizationHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OptimizationHistoryService historyService;

    @MockBean
    private AnalyticsEventService analyticsEventService;

    @Test
    void shouldReturnPagedHistory() throws Exception {
        UUID id = UUID.randomUUID();
        when(historyService.list(1, 10, null, null, null)).thenReturn(new OptimizationHistoryPage(
                List.of(new OptimizationHistorySummary(
                        id,
                        "FEATURE_DEVELOPMENT",
                        "给用户模块添加登录功能",
                        "mock",
                        "deterministic-enhancer-v1",
                        true,
                        12,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        "验收版本-20261001"
                )),
                1,
                10,
                1,
                1
        ));

        mockMvc.perform(get("/api/v1/optimization-history"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.data.records[0].templateCode").value("FEATURE_DEVELOPMENT"))
                .andExpect(jsonPath("$.data.records[0].modelVersion").value("验收版本-20261001"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10));
    }

    @Test
    void shouldClampPageSizeToMaximum() throws Exception {
        when(historyService.list(1, 50, null, null, null))
                .thenReturn(new OptimizationHistoryPage(List.of(), 0, 50, 1, 0));

        mockMvc.perform(get("/api/v1/optimization-history").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(50));
    }

    @Test
    void shouldPassHistoryFiltersToService() throws Exception {
        OffsetDateTime expectedFrom = OffsetDateTime.parse("2026-09-01T00:00:00Z");
        OffsetDateTime expectedTo = OffsetDateTime.parse("2026-09-25T00:00:00Z");
        when(historyService.list(1, 10, "AI 职业", expectedFrom, expectedTo))
                .thenReturn(new OptimizationHistoryPage(List.of(), 0, 10, 1, 0));

        mockMvc.perform(get("/api/v1/optimization-history")
                        .param("keyword", "  AI 职业 ")
                        .param("dateRange", "2026-09-01,2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldRejectInvalidHistoryDateRange() throws Exception {
        mockMvc.perform(get("/api/v1/optimization-history")
                        .param("dateRange", "2026-09-24,2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void shouldReturnHistoryDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(historyService.get(id)).thenReturn(new OptimizationHistoryDetail(
                id,
                "给用户模块添加登录功能",
                "## 任务目标\n实现登录功能",
                List.of(),
                Map.of("customDescription", "Spring Boot 用户服务"),
                List.of(),
                List.of(),
                "FEATURE_DEVELOPMENT",
                "mock",
                "deterministic-enhancer-v1",
                true,
                12,
                OffsetDateTime.now(ZoneOffset.UTC),
                true,
                false,
                List.of(),
                Map.of(),
                "验收版本-20261001"
        ));

        mockMvc.perform(get("/api/v1/optimization-history/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawPrompt").value("给用户模块添加登录功能"))
                .andExpect(jsonPath("$.data.contextSummary.customDescription").value("Spring Boot 用户服务"));
    }

    @Test
    void shouldDeleteHistory() throws Exception {
        mockMvc.perform(delete("/api/v1/optimization-history/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void shouldReoptimizeAndReturnNewResult() throws Exception {
        UUID id = UUID.randomUUID();
        OptimizationResult result = new OptimizationResult(
                "## 任务目标\n实现登录功能",
                List.of(),
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                List.of(),
                List.of(),
                TemplateCode.FEATURE_DEVELOPMENT,
                new ProviderMetadata("mock", "deterministic-enhancer-v1", true),
                5
        );
        when(historyService.reoptimize(id)).thenReturn(new ReoptimizationResult(id, result));

        mockMvc.perform(post("/api/v1/optimization-history/{id}/re-optimize", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value(id.toString()))
                .andExpect(jsonPath("$.data.result.provider.mock").value(true));
        verify(analyticsEventService).recordOptimizationSubmission(eq(true), any(HttpServletRequest.class));
    }
}
