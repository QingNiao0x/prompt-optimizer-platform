package com.promptoptimizer.analytics.controller;

import com.promptoptimizer.analytics.dto.AnalyticsViews.ModelCallPage;
import com.promptoptimizer.analytics.dto.AnalyticsViews.TokenRankingView;
import com.promptoptimizer.analytics.dto.ModelCallQuery;
import com.promptoptimizer.analytics.dto.TokenRankingQuery;
import com.promptoptimizer.analytics.service.AdminModelCallAnalyticsService;
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
 * 模型对账和 Token 排行仅供平台管理员；复用统一 401/403 与当前数据库角色复核。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminModelCallAnalyticsController {
    private final AdminModelCallAnalyticsService service;
    private final PlatformAdminAccess access;

    /** 仅装配协议和访问保护，聚合与范围验证交由应用服务。 */
    public AdminModelCallAnalyticsController(AdminModelCallAnalyticsService service, PlatformAdminAccess access) {
        this.service = service;
        this.access = access;
    }

    /** 查询完整日/周/月的匿名操作或模型来源 Token Top N。 */
    @GetMapping("/token-ranking")
    public ApiResponse<TokenRankingView> tokenRanking(@Valid TokenRankingQuery query, HttpServletRequest request) {
        // Spring Security 校验会话角色后再次复核，旧管理员会话不能绕过权限撤销。
        access.require();
        return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), service.tokenRanking(query));
    }

    /** 查询匿名调用明细，严格投影，不暴露完整 audit_event.details。 */
    @GetMapping("/model-calls")
    public ApiResponse<ModelCallPage> modelCalls(@Valid ModelCallQuery query, HttpServletRequest request) {
        access.require();
        return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), service.modelCalls(query));
    }
}
