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
    /** 已停用，仅兼容旧客户端和历史 journal 的反序列化；不采集、不入库、不参与查询。 */
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
    ADMIN_MODEL_CHANGED,
    /** 匿名租户级模型调用尝试对账；不进入账号活跃与业务使用次数统计。 */
    MODEL_CALL_RECORDED;

    /** 统一采集及投递边界，防止旧客户端或待恢复队列重新写入已停用的页面事件。 */
    public boolean isRecordable() {
        return this != APP_VISIT;
    }
}
