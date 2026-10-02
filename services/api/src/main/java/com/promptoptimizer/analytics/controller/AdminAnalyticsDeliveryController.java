package com.promptoptimizer.analytics.controller;

import com.promptoptimizer.analytics.infrastructure.AuditEventDelivery;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.security.PlatformAdminAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅平台管理员查看当前 API 实例的可靠投递状态；不暴露日志明细、磁盘路径或账号信息。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminAnalyticsDeliveryController {
    private final AuditEventDelivery delivery;
    private final PlatformAdminAccess adminAccess;

    /** 装配实例状态及数据库角色复核，不能把会话内旧角色当成最终授权。 */
    public AdminAnalyticsDeliveryController(AuditEventDelivery delivery, PlatformAdminAccess adminAccess) {
        this.delivery = delivery;
        this.adminAccess = adminAccess;
    }

    /** Security 先保证未登录 401/普通用户 403，再复核角色撤销；状态只描述本节点，不伪装集群总量。 */
    @GetMapping("/delivery")
    public ApiResponse<AuditEventDelivery.DeliveryStatus> delivery(HttpServletRequest request) {
        adminAccess.require();
        return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), delivery.status());
    }
}
