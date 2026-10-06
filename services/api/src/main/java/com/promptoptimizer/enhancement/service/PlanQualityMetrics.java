package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;

/**
 * 只记录有界指标，不将问题、答案、用户 ID 或文件内容作为指标标签。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface PlanQualityMetrics {

    /** 记录生成、展示和被过滤的问题数量，指标不包含问题正文。 */
    void generated(int received, int displayed);

    /** 记录一次受控 Provider 重试。 */
    void retry();

    /** 仅以枚举事件类型计数，不记录用户提交的答案内容。 */
    void event(Event event);

    /** 记录已提交入库的功能使用事件；标签仅允许直接增强提交与 Plan 完成两个固定代码。 */
    void featureUse(AnalyticsEventType eventType);

    /**
     * 可作为低基数指标标签的交互事件集合。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    enum Event { CANCELLED, CONFIRMED, CUSTOM_ANSWER, RESULT_EDITED, EXPIRED_RECOVERED }
}
