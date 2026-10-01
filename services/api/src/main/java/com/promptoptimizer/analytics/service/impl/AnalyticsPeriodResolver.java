package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.domain.AnalyticsRange;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * 将管理员提供的日历范围转换为时区明确的左闭右开数据库查询区间。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class AnalyticsPeriodResolver {

    /** 单次自然日查询至多覆盖一个闰年，避免 generate_series 产生无界结果集。 */
    private static final long MAX_QUERY_DAYS = 366;
    private static final LocalDate MIN_DATE = LocalDate.of(1, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

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
     * 解析仪表盘范围；结束日期包含在内，至多 366 个自然日，数据库使用排他结束时刻。
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
                } catch (DateTimeException exception) {
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

    /** 将外部范围代码归一化，非法代码返回稳定的参数错误。 */
    private AnalyticsRange parseRange(String value) {
        try {
            return AnalyticsRange.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new InvalidOptimizationRequestException(
                    "统计范围仅支持 TODAY、YESTERDAY、THIS_WEEK、THIS_MONTH、LAST_MONTH 或 CUSTOM"
            );
        }
    }

    /** 限定四位公历年份，先拒绝扩展年份和年零，再进行日期运算。 */
    private LocalDate parseDate(String value, String name) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new DateTimeParseException("invalid format", "", 0);
            }
            LocalDate date = LocalDate.parse(value);
            if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
                throw new InvalidOptimizationRequestException(name + " 必须在 0001-01-01 至 9999-12-31 之间");
            }
            return date;
        } catch (DateTimeParseException exception) {
            throw new InvalidOptimizationRequestException(name + " 必须使用 YYYY-MM-DD 日期格式");
        }
    }

    /** 校验展开后的周期及日桶上限，按当地午夜构造边界以保留夏令时语义。 */
    private AnalyticsPeriod period(LocalDate from, LocalDate toExclusive) {
        if (!from.isBefore(toExclusive)) {
            throw new InvalidOptimizationRequestException("统计结束日期必须晚于开始日期");
        }
        if (from.isBefore(MIN_DATE) || toExclusive.minusDays(1).isAfter(MAX_DATE)) {
            throw new InvalidOptimizationRequestException("完整统计周期必须在 0001-01-01 至 9999-12-31 之间");
        }
        if (ChronoUnit.DAYS.between(from, toExclusive) > MAX_QUERY_DAYS) {
            throw new InvalidOptimizationRequestException("单次统计范围不能超过 366 个自然日，请分段查询");
        }
        try {
            OffsetDateTime start = from.atStartOfDay(zoneId).toOffsetDateTime();
            OffsetDateTime end = toExclusive.atStartOfDay(zoneId).toOffsetDateTime();
            return new AnalyticsPeriod(from, toExclusive, zoneId, start, end);
        } catch (DateTimeException exception) {
            throw new InvalidOptimizationRequestException("统计日期超出当前时区支持的范围");
        }
    }
}
