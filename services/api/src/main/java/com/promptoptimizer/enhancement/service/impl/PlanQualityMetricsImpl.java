package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.service.PlanQualityMetrics;
import com.promptoptimizer.enhancement.service.PlanQualityMetrics.Event;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 只记录有界指标，不将问题、答案、用户 ID 或文件内容作为指标标签。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class PlanQualityMetricsImpl implements PlanQualityMetrics {
    private final MeterRegistry registry;
    private final Counter directSubmissions;
    private final Counter completedPlans;

    /** 预注册固定的两组标签，即使当前没有使用也可读取零值；不按请求创建新标签。 */
    public PlanQualityMetricsImpl(MeterRegistry registry) {
        this.registry = registry;
        this.directSubmissions = registry.counter("optimization.feature.uses", "event",
                AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED.name());
        this.completedPlans = registry.counter("optimization.feature.uses", "event",
                AnalyticsEventType.PLAN_COMPLETED.name());
    }
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

    /** 只接受服务端功能事件白名单，客户端交互事件不能冒充一次完整 Plan。 */
    @Override
    public void featureUse(AnalyticsEventType eventType) {
        if (eventType == null) throw new IllegalArgumentException("功能使用事件不能为空");
        switch (eventType) {
            case DIRECT_OPTIMIZATION_SUBMITTED -> directSubmissions.increment();
            case PLAN_COMPLETED -> completedPlans.increment();
            default -> throw new IllegalArgumentException("不是可计数的功能使用事件");
        }
    }
}
