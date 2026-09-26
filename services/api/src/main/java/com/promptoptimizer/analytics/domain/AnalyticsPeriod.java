package com.promptoptimizer.analytics.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 经验证的统计日历区间，结束日期和结束时刻均为排他边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AnalyticsPeriod(
        LocalDate fromDate,
        LocalDate toDateExclusive,
        ZoneId zoneId,
        OffsetDateTime fromInclusive,
        OffsetDateTime toExclusive
) {

    public AnalyticsPeriod {
        Objects.requireNonNull(fromDate, "fromDate must not be null");
        Objects.requireNonNull(toDateExclusive, "toDateExclusive must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        Objects.requireNonNull(fromInclusive, "fromInclusive must not be null");
        Objects.requireNonNull(toExclusive, "toExclusive must not be null");
        if (!fromDate.isBefore(toDateExclusive)) {
            throw new IllegalArgumentException("统计区间必须至少包含一天");
        }
    }

    /** 返回该区间包含的自然日数量。 */
    public long dayCount() {
        return ChronoUnit.DAYS.between(fromDate, toDateExclusive);
    }
}
