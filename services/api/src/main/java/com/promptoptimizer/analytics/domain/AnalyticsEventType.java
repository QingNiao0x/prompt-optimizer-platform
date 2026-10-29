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
    /** 素材工具（含旧图像任务）通过校验及幂等预留后的操作，包含直接生成、Plan和再次优化。 */
    IMAGE_PROMPT_SUBMITTED,
    /** 素材直接生成提交的细分事实，与通用提交共用操作编号，不再次累计关键操作。 */
    IMAGE_PROMPT_DIRECT_SUBMITTED,
    /** 素材任务Plan已就绪（含零问题计划）；独立展示，不再累计关键操作次数。 */
    IMAGE_PROMPT_PLAN_CREATED,
    /** 素材Plan最终提示词已生成且可恢复；按计划编号去重，补存历史和重放不重计。 */
    IMAGE_PROMPT_PLAN_COMPLETED,
    /** 已生成可交付的素材提示词（兼容旧图像任务）；历史暂未保存也计结果，查询和补存不重计。 */
    IMAGE_PROMPT_GENERATED,
    /** 基于历史当前正文再次优化成功的细分事实，不重复累计通用生成或关键操作。 */
    IMAGE_PROMPT_REOPTIMIZED,
    /** 文章工具通过校验及幂等预留后的提交，包含原文重生和当前稿继续润色。 */
    TEXT_POLISH_SUBMITTED,
    /** 已通过事实保护检查并生成可恢复的润色结果，不代表供应商计费次数。 */
    TEXT_POLISH_GENERATED,
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
