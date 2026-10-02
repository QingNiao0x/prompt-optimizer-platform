package com.promptoptimizer.analytics.controller;

import com.promptoptimizer.analytics.dto.ClientAnalyticsEventRequest;
import com.promptoptimizer.analytics.dto.ClientAnalyticsContext;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/analytics")
public class AnalyticsEventController {

    private final AnalyticsEventService analyticsEventService;

    /** 委托可靠接收应用服务；Controller 不解析身份、事件时间或文件持久化。 */
    public AnalyticsEventController(AnalyticsEventService analyticsEventService) {
        this.analyticsEventService = analyticsEventService;
    }

    /** 接收一次页面访问或结果导出事件；访客身份不能由客户端指定。 */
    @PostMapping("/events")
    public ApiResponse<Void> record(
            @Valid @RequestBody ClientAnalyticsEventRequest event,
            HttpServletRequest request
    ) {
        analyticsEventService.recordClientEvent(event, request);
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ApiResponse.success(requestId, null);
    }

    /** 登录账号才能查询审计关联号；它不具备认证能力，也不能决定新事件所有者。 */
    @GetMapping("/context")
    public ApiResponse<ClientAnalyticsContext> context(HttpServletRequest request) {
        return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                analyticsEventService.clientContext(request));
    }
}
