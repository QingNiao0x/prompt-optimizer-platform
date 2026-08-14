package com.promptoptimizer.enhancement.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.enhancement.application.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外提供提示词增强能力的 REST 控制器。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/optimizations")
public class OptimizationController {

    private final EnhancementOrchestrator enhancementOrchestrator;

    public OptimizationController(EnhancementOrchestrator enhancementOrchestrator) {
        this.enhancementOrchestrator = enhancementOrchestrator;
    }

    /**
     * 执行一次提示词增强，并返回结构化结果和上下文分析报告。
     */
    @PostMapping
    public ApiResponse<OptimizationResult> optimize(
            @Valid @RequestBody OptimizationRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = (String) httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ApiResponse.success(requestId, enhancementOrchestrator.optimize(request));
    }
}
