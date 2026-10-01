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
 * Security 先处理未登录和普通账号，Controller 再复核数据库当前角色，避免旧会话保留已撤销的管理员权限。
 * 平台审计允许跨租户查询，不能将这里的账号筛选条件视为授予普通用户的数据访问权限。
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

    /** 将协议层绑定到统计应用服务及当前角色复核，不在 Controller 中计算指标或拼接 SQL。 */
    public AdminAnalyticsController(
            AdminAnalyticsService analyticsService,
            PlatformAdminAccess platformAdminAccess
    ) {
        this.analyticsService = analyticsService;
        this.platformAdminAccess = platformAdminAccess;
    }

    /**
     * 查询访问、账号、活跃、设备、使用时段、月份和充值统计。
     *
     * @param query 经 Bean Validation 校验的日期与账号组合条件
     * @param request 用于读取服务端 requestId，不采信客户端指定的查询身份
     * @return 带 requestId 的管理员仪表盘响应
     */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardView> dashboard(@Valid DashboardQuery query, HttpServletRequest request) {
        // 会话角色通过安全拦截后，仍复核数据库当前权限，阻止被撤销角色的旧会话继续查询。
        platformAdminAccess.require();
        return ApiResponse.success(requestId(request), analyticsService.dashboard(query));
    }

    /**
     * 按日期锚点查询日、周或月使用频率排行。
     *
     * @param query 周期、日期、账号组合条件和排行条数
     * @param request 当前请求的链路上下文
     * @return 实际展开的周期及账号排行
     */
    @GetMapping("/usage-ranking")
    public ApiResponse<RankingView> usageRanking(@Valid UsageRankingQuery query, HttpServletRequest request) {
        platformAdminAccess.require();
        return ApiResponse.success(requestId(request), analyticsService.usageRanking(query));
    }

    /**
     * 按账号、事件类型和日期范围分页查看关键操作地点日志。
     *
     * @param query 日期、账号关键词、可选事件类型和分页条件
     * @param request 当前请求的链路上下文
     * @return 安全投影的日志页，不返回完整审计 JSON
     */
    @GetMapping("/operations")
    public ApiResponse<OperationLogPage> operations(
            @Valid OperationLogQuery query,
            HttpServletRequest request
    ) {
        platformAdminAccess.require();
        return ApiResponse.success(requestId(request), analyticsService.operationLogs(query));
    }

    /** 沿用 RequestIdFilter 生成或校验后的标识，使统计错误与请求日志能关联定位。 */
    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
