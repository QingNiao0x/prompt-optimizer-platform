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
    /** 无 Plan 的增强提交尝试；作为细分事实，不再次累计通用关键操作次数。 */
    DIRECT_OPTIMIZATION_SUBMITTED,
    /** 有效计划完成最终生成及历史保存；每个计划仅保留一次完成事实。 */
    PLAN_COMPLETED,
    PLAN_CREATED,
    CONTEXT_PREPARED,
    CONTEXT_ANALYZED,
    RESULT_EXPORTED,
    RECHARGE_PAID,
    ADMIN_MODEL_CHANGED
}
