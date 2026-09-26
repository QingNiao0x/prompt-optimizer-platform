package com.promptoptimizer.enhancement.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 只记录有界指标，不将问题、答案、用户 ID 或文件内容作为指标标签。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class PlanQualityMetrics {
    private final MeterRegistry registry;
    public PlanQualityMetrics(MeterRegistry registry) { this.registry = registry; }
    /** 记录生成、展示和被过滤的问题数量，指标不包含问题正文。 */
    public void generated(int received, int displayed) {
        registry.summary("planning.questions.received").record(received);
        registry.summary("planning.questions.displayed").record(displayed);
        registry.counter("planning.questions.filtered").increment(received - displayed);
    }
    /** 记录一次受控 Provider 重试。 */
    public void retry() { registry.counter("planning.provider.retries").increment(); }

    /** 仅以枚举事件类型计数，不记录用户提交的答案内容。 */
    public void event(Event event) {
        registry.counter("planning.interactions", "event", event.name()).increment();
    }
    /**
     * 可作为低基数指标标签的交互事件集合。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public enum Event { CANCELLED, CONFIRMED, CUSTOM_ANSWER, RESULT_EDITED, EXPIRED_RECOVERED }
}
