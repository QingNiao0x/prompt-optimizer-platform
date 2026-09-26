package com.promptoptimizer.context.controller;

import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.enhancement.service.PlanningSessionService;
import com.promptoptimizer.enhancement.domain.PlanningContextPreparation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为上下文感知的 Plan Mode 准备短期、安全摘要。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/context/planning")
public class PlanningContextController {

    private final PlanningSessionService planningSessionService;
    private final AnalyticsEventService analyticsEventService;

    public PlanningContextController(
            PlanningSessionService planningSessionService,
            AnalyticsEventService analyticsEventService
    ) {
        this.planningSessionService = planningSessionService;
        this.analyticsEventService = analyticsEventService;
    }

    /** 对本次请求上下文生成安全摘要与短期引用，供后续计划流程使用。 */
    @PostMapping
    public ApiResponse<PlanningContextPreparation> prepare(
            @Valid @RequestBody PlanningContextRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = (String) httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        PlanningContextPreparation preparation = planningSessionService.prepareContext(request);
        analyticsEventService.record(AnalyticsEventType.CONTEXT_PREPARED, httpRequest);
        return ApiResponse.success(requestId, preparation);
    }
}
