package com.promptoptimizer.enhancement.controller;

import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.enhancement.service.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.service.OptimizationPlanningService;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.history.service.OptimizationHistoryService;
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
    private final OptimizationPlanningService optimizationPlanningService;
    private final OptimizationHistoryService optimizationHistoryService;
    private final AnalyticsEventService analyticsEventService;

    public OptimizationController(
            EnhancementOrchestrator enhancementOrchestrator,
            OptimizationPlanningService optimizationPlanningService,
            OptimizationHistoryService optimizationHistoryService,
            AnalyticsEventService analyticsEventService
    ) {
        this.enhancementOrchestrator = enhancementOrchestrator;
        this.optimizationPlanningService = optimizationPlanningService;
        this.optimizationHistoryService = optimizationHistoryService;
        this.analyticsEventService = analyticsEventService;
    }

    /**
     * 识别真正影响结果的业务问题；可引用预先生成的安全上下文摘要，不接收文件正文，也不保存历史记录。
     */
    @PostMapping("/plan")
    public ApiResponse<OptimizationPlan> plan(
            @Valid @RequestBody OptimizationPlanRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = (String) httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        OptimizationPlan plan = optimizationPlanningService.plan(request);
        analyticsEventService.record(AnalyticsEventType.PLAN_CREATED, httpRequest);
        return ApiResponse.success(requestId, plan);
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
        analyticsEventService.recordOptimizationSubmission(request.planConfirmation() == null, httpRequest);
        OptimizationResult result = enhancementOrchestrator.optimize(request);
        optimizationHistoryService.save(request, result);
        // 编排器已经验证计划所有权与全部回答；只有生成和历史保存都成功才记录一次完成。
        if (request.planConfirmation() != null) {
            analyticsEventService.recordPlanCompleted(request.planConfirmation().planId(), httpRequest);
        }
        return ApiResponse.success(requestId, result);
    }
}
