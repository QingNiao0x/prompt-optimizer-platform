package com.promptoptimizer.enhancement.api;

import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.application.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.history.application.OptimizationHistoryService;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OptimizationController.class)
@Import(RequestIdFilter.class)
class OptimizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EnhancementOrchestrator orchestrator;

    @MockBean
    private OptimizationHistoryService optimizationHistoryService;

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
    }
}
