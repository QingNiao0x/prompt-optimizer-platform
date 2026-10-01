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

    /** 使用部署配置的统计时区；Clock 保持 UTC，由日历解析显式切换到统计时区。 */
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
     * 本周/本月只统计到今天，上月覆盖完整自然月；自定义的两个端点均为包含的日历日。
     *
     * @param rangeValue 范围枚举代码，接口 DTO 在未传值时默认 TODAY
     * @param fromValue 自定义开始日；预设范围不使用此值
     * @param toValue 自定义结束日；预设范围不使用此值
     * @return 可供 SQL 过滤、补零及页面展示共用的时区明确区间
     * @throws InvalidOptimizationRequestException 范围、日期格式、日期顺序或日数越界时抛出
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
                // 周一为周起点；仪表盘展示截至今天的累计值，不含本周尚未发生的日期。
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
                    // 前端选择的结束日包含在内，数据库用次日午夜作为排他终点，避免漏掉当晚事件。
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

    /**
     * 解析排行锚点所在的完整日、周或月；排行周期与仪表盘本周/本月至今的语义不同。
     *
     * @param periodValue DAY、WEEK 或 MONTH，允许大小写与边缘空白归一化
     * @param dateValue YYYY-MM-DD 锚点日，用于定位所在的完整周期
     * @return 已验证边界的完整排行区间
     * @throws InvalidOptimizationRequestException 周期代码、日期或展开后的边界不合法时抛出
     */
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
            // 自然日不固定为 24 小时；两端分别按当地午夜解析，夏令时切换仍能保留全部事件。
            OffsetDateTime start = from.atStartOfDay(zoneId).toOffsetDateTime();
            OffsetDateTime end = toExclusive.atStartOfDay(zoneId).toOffsetDateTime();
            return new AnalyticsPeriod(from, toExclusive, zoneId, start, end);
        } catch (DateTimeException exception) {
            throw new InvalidOptimizationRequestException("统计日期超出当前时区支持的范围");
        }
    }
}
