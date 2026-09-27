package com.promptoptimizer.analytics.controller;

import com.promptoptimizer.analytics.dto.AnalyticsViews.DashboardView;
import com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLogPage;
import com.promptoptimizer.analytics.dto.AnalyticsViews.RankingView;
import com.promptoptimizer.analytics.dto.DashboardQuery;
import com.promptoptimizer.analytics.dto.OperationLogQuery;
import com.promptoptimizer.analytics.dto.UsageRankingQuery;
import com.promptoptimizer.analytics.service.AdminAnalyticsService;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.security.PlatformAdminAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理员专用统计与运维审计查询接口；Spring Security 和数据库角色复核双重拦截。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminAnalyticsController {

    private final AdminAnalyticsService analyticsService;
    private final PlatformAdminAccess platformAdminAccess;

    public AdminAnalyticsController(
            AdminAnalyticsService analyticsService,
            PlatformAdminAccess platformAdminAccess
    ) {
        this.analyticsService = analyticsService;
        this.platformAdminAccess = platformAdminAccess;
    }

    /**
     * 查询访问、账号、活跃、设备、使用时段、月份和充值统计。
     */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardView> dashboard(@Valid DashboardQuery query, HttpServletRequest request) {
        platformAdminAccess.require();
        return ApiResponse.success(requestId(request), analyticsService.dashboard(query));
    }

    /**
     * 按日期锚点查询日、周或月使用频率排行。
     */
    @GetMapping("/usage-ranking")
    public ApiResponse<RankingView> usageRanking(@Valid UsageRankingQuery query, HttpServletRequest request) {
        platformAdminAccess.require();
        return ApiResponse.success(requestId(request), analyticsService.usageRanking(query));
    }

    /**
     * 按账号、事件类型和日期范围分页查看关键操作地点日志。
     */
    @GetMapping("/operations")
    public ApiResponse<OperationLogPage> operations(
            @Valid OperationLogQuery query,
            HttpServletRequest request
    ) {
        platformAdminAccess.require();
        return ApiResponse.success(requestId(request), analyticsService.operationLogs(query));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
