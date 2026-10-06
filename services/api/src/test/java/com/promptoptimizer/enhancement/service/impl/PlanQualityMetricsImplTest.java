package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证功能使用指标只有两个固定事件标签，不按账号、计划或正文扩张基数。 */
class PlanQualityMetricsImplTest {
    @Test
    void registersZeroCountersAndCountsOnlyTheTwoBoundedEventTypes() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new PlanQualityMetricsImpl(registry);
            assertThat(registry.find("optimization.feature.uses").counters()).hasSize(2)
                    .allSatisfy(counter -> assertThat(counter.count()).isZero());
            metrics.featureUse(AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED);
            metrics.featureUse(AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED);
            metrics.featureUse(AnalyticsEventType.PLAN_COMPLETED);
            assertThat(registry.get("optimization.feature.uses").tag("event", "DIRECT_OPTIMIZATION_SUBMITTED").counter().count()).isEqualTo(2);
            assertThat(registry.get("optimization.feature.uses").tag("event", "PLAN_COMPLETED").counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter -> {
                assertThat(meter.getId().getTags()).hasSize(1);
                assertThat(meter.getId().getTags().getFirst().getKey()).isEqualTo("event");
                assertThat(meter.getId().getTags().getFirst().getValue()).isIn("DIRECT_OPTIMIZATION_SUBMITTED", "PLAN_COMPLETED");
            });
        } finally { registry.close(); }
    }

    @Test
    void rejectsNullAndEveryOtherEventWithoutRegisteringNewTags() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new PlanQualityMetricsImpl(registry);
            assertThatThrownBy(() -> metrics.featureUse(null)).isInstanceOf(IllegalArgumentException.class);
            for (var event : AnalyticsEventType.values()) {
                if (event == AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED || event == AnalyticsEventType.PLAN_COMPLETED) continue;
                assertThatThrownBy(() -> metrics.featureUse(event)).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(registry.getMeters()).hasSize(2);
            assertThat(registry.find("optimization.feature.uses").counters()).allSatisfy(counter -> assertThat(counter.count()).isZero());
        } finally { registry.close(); }
    }
}
