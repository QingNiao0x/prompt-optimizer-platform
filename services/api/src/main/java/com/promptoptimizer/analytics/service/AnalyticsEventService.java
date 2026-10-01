package com.promptoptimizer.analytics.service;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.ClientAnalyticsEventType;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 记录服务端确认的审计行为；优化提交统计请求尝试，其他事件按各流程完成点记录。
 * 调用方只提交事件类型和当前请求，不提交账号 ID。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface AnalyticsEventService {

    /** 记录一次成功登录，并把登录位置快照绑定到当前服务端会话。 */
    void recordLogin(HttpServletRequest request);

    /** 记录退出事件后清理服务端会话中的统计关联信息。 */
    void recordLogout(HttpServletRequest request);

    /** 记录后端确认的关键操作；OPTIMIZATION_SUBMITTED 不代表模型执行成功。 */
    void record(AnalyticsEventType eventType, HttpServletRequest request);

    /** 把经过浏览器事件白名单验证的遥测类型映射为审计事件。 */
    void recordClientEvent(ClientAnalyticsEventType eventType, HttpServletRequest request);
}
