package com.promptoptimizer.context.controller;

import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.analytics.service.impl.AnalyticsEventService;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.service.ContextAnalyzer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目上下文分析接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/context")
public class ContextAnalysisController {

    private final ContextAnalyzer contextAnalyzer;
    private final AnalyticsEventService analyticsEventService;

    public ContextAnalysisController(ContextAnalyzer contextAnalyzer, AnalyticsEventService analyticsEventService) {
        this.contextAnalyzer = contextAnalyzer;
        this.analyticsEventService = analyticsEventService;
    }

    /**
     * 分析用户主动提交的项目描述与文件内容，返回脱敏后的上下文快照。
     */
    @PostMapping("/analyze")
    public ApiResponse<ContextSnapshot> analyze(
            @Valid @RequestBody ContextAnalysisRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = (String) httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        ContextSnapshot snapshot = contextAnalyzer.analyze(request);
        analyticsEventService.record(AnalyticsEventType.CONTEXT_ANALYZED, httpRequest);
        return ApiResponse.success(requestId, snapshot);
    }
}
