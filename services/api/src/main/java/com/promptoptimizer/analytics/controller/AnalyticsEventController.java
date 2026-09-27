package com.promptoptimizer.analytics.controller;

import com.promptoptimizer.analytics.dto.ClientAnalyticsEventRequest;
import com.promptoptimizer.analytics.service.impl.AnalyticsEventService;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已登录浏览器的窄遥测入口，只接受事件白名单并由服务端绑定身份与网络信息。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/analytics/events")
public class AnalyticsEventController {

    private final AnalyticsEventService analyticsEventService;

    public AnalyticsEventController(AnalyticsEventService analyticsEventService) {
        this.analyticsEventService = analyticsEventService;
    }

    /** 接收一次页面访问或结果导出事件；访客身份不能由客户端指定。 */
    @PostMapping
    public ApiResponse<Void> record(
            @Valid @RequestBody ClientAnalyticsEventRequest event,
            HttpServletRequest request
    ) {
        analyticsEventService.recordClientEvent(event.eventType(), request);
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ApiResponse.success(requestId, null);
    }
}
