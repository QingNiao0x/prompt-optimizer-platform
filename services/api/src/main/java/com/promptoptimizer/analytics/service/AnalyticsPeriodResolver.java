package com.promptoptimizer.analytics.service;

import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.domain.AnalyticsRange;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 将管理员提供的日历范围转换为时区明确的左闭右开数据库查询区间。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class AnalyticsPeriodResolver {

    private final ZoneId zoneId;
    private final Clock clock;

    @Autowired
    public AnalyticsPeriodResolver(@Value("${app.analytics.zone-id:Asia/Shanghai}") String zoneId) {
        this(ZoneId.of(zoneId), Clock.systemUTC());
    }

    AnalyticsPeriodResolver(ZoneId zoneId, Clock clock) {
        this.zoneId = zoneId;
        this.clock = clock;
    }

    /**
     * 解析仪表盘范围；自定义输入的结束日期包含在内，数据库边界统一转换成排他结束时刻。
     */
    public AnalyticsPeriod resolve(String rangeValue, String fromValue, String toValue) {
        AnalyticsRange range = parseRange(rangeValue);
        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        LocalDate from;
        LocalDate toExclusive;
        switch (range) {
            case TODAY -> {
                from = today;
                toExclusive = today.plusDays(1);
            }
            case YESTERDAY -> {
                from = today.minusDays(1);
                toExclusive = today;
            }
            case THIS_WEEK -> {
                from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                toExclusive = today.plusDays(1);
            }
            case THIS_MONTH -> {
                from = today.withDayOfMonth(1);
                toExclusive = today.plusDays(1);
            }
            case LAST_MONTH -> {
                toExclusive = today.withDayOfMonth(1);
                from = toExclusive.minusMonths(1);
            }
            case CUSTOM -> {
                from = parseDate(fromValue, "fromDate");
                try {
                    toExclusive = parseDate(toValue, "toDate").plusDays(1);
                } catch (java.time.DateTimeException exception) {
                    throw new InvalidOptimizationRequestException("toDate 超出支持的日期范围");
                }
                if (toExclusive.isBefore(from) || toExclusive.equals(from)) {
                    throw new InvalidOptimizationRequestException("自定义结束日期必须不早于开始日期");
                }
            }
            default -> throw new IllegalStateException("未处理的统计时间范围");
        }
        return period(from, toExclusive);
    }

    /** 解析排行榜所需的日、周或月范围。 */
    public AnalyticsPeriod resolveRanking(String periodValue, String dateValue) {
        String normalized = periodValue == null ? "" : periodValue.trim().toUpperCase(Locale.ROOT);
        LocalDate date = parseDate(dateValue, "date");
        return switch (normalized) {
            case "DAY" -> period(date, date.plusDays(1));
            case "WEEK" -> {
                LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield period(monday, monday.plusWeeks(1));
            }
            case "MONTH" -> {
                LocalDate month = date.withDayOfMonth(1);
                yield period(month, month.plusMonths(1));
            }
            default -> throw new InvalidOptimizationRequestException("排行周期仅支持 DAY、WEEK 或 MONTH");
        };
    }

    private AnalyticsRange parseRange(String value) {
        try {
            return AnalyticsRange.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new InvalidOptimizationRequestException(
                    "统计范围仅支持 TODAY、YESTERDAY、THIS_WEEK、THIS_MONTH、LAST_MONTH 或 CUSTOM"
            );
        }
    }

    private LocalDate parseDate(String value, String name) {
        try {
            if (value == null || value.isBlank()) {
                throw new DateTimeParseException("missing", "", 0);
            }
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidOptimizationRequestException(name + " 必须使用 YYYY-MM-DD 日期格式");
        }
    }

    private AnalyticsPeriod period(LocalDate from, LocalDate toExclusive) {
        if (!from.isBefore(toExclusive)) {
            throw new InvalidOptimizationRequestException("统计结束日期必须晚于开始日期");
        }
        OffsetDateTime start = from.atStartOfDay(zoneId).toOffsetDateTime();
        OffsetDateTime end = toExclusive.atStartOfDay(zoneId).toOffsetDateTime();
        return new AnalyticsPeriod(from, toExclusive, zoneId, start, end);
    }
}
