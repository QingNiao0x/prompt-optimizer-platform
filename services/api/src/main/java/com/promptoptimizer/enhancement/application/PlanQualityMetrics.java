package com.promptoptimizer.enhancement.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 只记录有界指标，不将问题、答案、用户 ID 或文件内容作为指标标签。 */
@Component
public class PlanQualityMetrics {
    private final MeterRegistry registry;
    public PlanQualityMetrics(MeterRegistry registry) { this.registry = registry; }
    public void generated(int received, int displayed) {
        registry.summary("planning.questions.received").record(received);
        registry.summary("planning.questions.displayed").record(displayed);
        registry.counter("planning.questions.filtered").increment(received - displayed);
    }
    public void retry() { registry.counter("planning.provider.retries").increment(); }
    public void event(Event event) {
        registry.counter("planning.interactions", "event", event.name()).increment();
    }
    public enum Event { CANCELLED, CONFIRMED, CUSTOM_ANSWER, RESULT_EDITED, EXPIRED_RECOVERED }
}
