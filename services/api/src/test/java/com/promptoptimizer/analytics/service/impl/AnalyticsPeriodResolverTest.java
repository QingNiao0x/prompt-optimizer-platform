package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证统计自然日范围、时区边界和自定义日期输入。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AnalyticsPeriodResolverTest {

    private final AnalyticsPeriodResolver resolver = new AnalyticsPeriodResolver(
            ZoneId.of("Asia/Shanghai"),
            Clock.fixed(Instant.parse("2026-09-25T02:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void todayAndCurrentWeekStopAtTodayInConfiguredZone() {
        AnalyticsPeriod today = resolver.resolve("TODAY", null, null);
        AnalyticsPeriod yesterday = resolver.resolve("YESTERDAY", null, null);
        AnalyticsPeriod week = resolver.resolve("THIS_WEEK", null, null);
        AnalyticsPeriod month = resolver.resolve("THIS_MONTH", null, null);

        assertThat(today.fromInclusive()).hasToString("2026-09-25T00:00+08:00");
        assertThat(today.toExclusive()).hasToString("2026-09-26T00:00+08:00");
        assertThat(yesterday.fromDate()).hasToString("2026-09-24");
        assertThat(yesterday.toDateExclusive()).hasToString("2026-09-25");
        assertThat(week.fromDate()).hasToString("2026-09-21");
        assertThat(week.toDateExclusive()).hasToString("2026-09-26");
        assertThat(week.dayCount()).isEqualTo(5);
        assertThat(month.fromDate()).hasToString("2026-09-01");
        assertThat(month.toDateExclusive()).hasToString("2026-09-26");
    }

    @Test
    void lastMonthAndCustomRangeUseInclusiveUiDatesAndExclusiveStorageBoundary() {
        AnalyticsPeriod lastMonth = resolver.resolve("LAST_MONTH", null, null);
        AnalyticsPeriod custom = resolver.resolve("CUSTOM", "2026-09-03", "2026-09-05");

        assertThat(lastMonth.fromDate()).hasToString("2026-08-01");
        assertThat(lastMonth.toDateExclusive()).hasToString("2026-09-01");
        assertThat(custom.fromDate()).hasToString("2026-09-03");
        assertThat(custom.toDateExclusive()).hasToString("2026-09-06");
        assertThat(custom.dayCount()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidRangeAndReversedCustomDates() {
        assertThatThrownBy(() -> resolver.resolve("UNKNOWN", null, null))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> resolver.resolve("CUSTOM", "2026-09-05", "2026-09-04"))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("不早于");
        assertThatThrownBy(() -> resolver.resolve("CUSTOM", "2026-02-30", "2026-03-01"))
                .isInstanceOf(InvalidOptimizationRequestException.class)
                .hasMessageContaining("YYYY-MM-DD");
    }
}
