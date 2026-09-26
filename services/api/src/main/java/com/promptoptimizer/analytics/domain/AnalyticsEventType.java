package com.promptoptimizer.analytics.domain;

/**
 * 统计与审计允许记录的关键业务事件。事件中不包含提示词正文、凭据或原始请求头。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum AnalyticsEventType {
    LOGIN,
    LOGOUT,
    APP_VISIT,
    OPTIMIZATION_SUBMITTED,
    PLAN_CREATED,
    CONTEXT_PREPARED,
    CONTEXT_ANALYZED,
    RESULT_EXPORTED,
    RECHARGE_PAID,
    ADMIN_MODEL_CHANGED
}
