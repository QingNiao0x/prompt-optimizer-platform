package com.promptoptimizer.enhancement.controller;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.*;
import com.promptoptimizer.enhancement.service.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.history.service.OptimizationHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * V2优化入口：缺省及COMPACT进入独立自然链，STRUCTURED保留原增强实现。
 * Plan问题使用新策略；最终两种形态仍复用服务端题答绑定，偏好不改变用户事实或授权。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v2/optimizations")
public class DocumentOptimizationController {
    private final NaturalEnhancementService natural;
    private final EnhancementOrchestrator structured;
    private final OptimizationHistoryService history;
    private final AnalyticsEventService analytics;

    public DocumentOptimizationController(NaturalEnhancementService natural, EnhancementOrchestrator structured,
            OptimizationHistoryService history, AnalyticsEventService analytics) {
        this.natural = natural;
        this.structured = structured;
        this.history = history;
        this.analytics = analytics;
    }

    /** Plan生成不保存优化记录，支持零问题以及按题回答，所有者始终来自认证上下文。 */
    @PostMapping("/plan")
    public ApiResponse<OptimizationPlan> plan(@Valid @RequestBody OptimizationPlanRequest input, HttpServletRequest request) {
        OptimizationPlan plan = natural.plan(input);
        analytics.record(AnalyticsEventType.PLAN_CREATED, request);
        return ApiResponse.success(requestId(request), plan);
    }

    /** 一次成功生成只保存一次，失败不会覆盖当前结果或被计成Plan完成。 */
    @PostMapping
    public ApiResponse<PromptDocument> optimize(@Valid @RequestBody OptimizationRequest input, HttpServletRequest request) {
        analytics.recordOptimizationSubmission(input.planConfirmation() == null, request);
        PromptDocument result = input.enhancement().promptFormat() == PromptFormat.STRUCTURED
                ? PromptDocument.structured(structured.optimize(input)) : natural.optimize(input);
        result = result.withRecordId(history.saveDocument(input, result));
        if (input.planConfirmation() != null) analytics.recordPlanCompleted(input.planConfirmation().planId(), request);
        return ApiResponse.success(requestId(request), result);
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
