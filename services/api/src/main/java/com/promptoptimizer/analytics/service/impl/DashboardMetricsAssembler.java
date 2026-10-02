package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DailyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DeviceMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.HourlyMetric;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper.DashboardMetricRow;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper.UsageCounts;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 将融合查询的内部行转换成既有仪表盘序列，并拒绝缺桶、重复桶或不合法的聚合结果。
 * 这里只处理已验证区间的数据库结果；异常属于内部数据契约错误，不能补零伪装为查询成功。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class DashboardMetricsAssembler {

    private DashboardMetricsAssembler() {
    }

    /**
     * 校验并组装汇总、完整自然日、24 小时及登录设备序列，返回不可变结果。
     *
     * @param period 服务已解析的左闭右开自然日区间
     * @param rows Mapper 返回的四类内部行；设备子序列沿用数据库排序，日期/小时按桶重新排序
     * @return 保留原仪表盘计数和排序语义的不可变序列
     * @throws IllegalStateException 结果缺失、重复、越界、包含负数或与汇总不一致时抛出
     */
    static Metrics assemble(AnalyticsPeriod period, List<DashboardMetricRow> rows) {
        require(period != null && rows != null, "缺少区间或聚合行");
        UsageCounts summary = null;
        Map<LocalDate, DailyMetric> days = new TreeMap<>();
        Map<Integer, HourlyMetric> hours = new TreeMap<>();
        Map<String, DeviceMetric> devices = new LinkedHashMap<>();
        long previousDeviceLogins = Long.MAX_VALUE;
        for (DashboardMetricRow row : rows) {
            require(row != null && row.kind() != null, "缺少聚合类型");
            requireNonNegative(row);
            switch (row.kind()) {
                case SUMMARY -> {
                    require(summary == null && row.bucket() == null, "汇总行重复或含有桶键");
                    requireZero(row.newAccounts(), row.operationCount(), row.loginCount(), row.uniqueUsers());
                    requireUserCounts(row);
                    summary = new UsageCounts(row.accessCount(), row.uniqueVisitors(), row.activeUsers(), row.actualUsers());
                }
                case DAILY -> {
                    requireZero(row.operationCount(), row.loginCount(), row.uniqueUsers());
                    requireUserCounts(row);
                    LocalDate date = parseDay(row.bucket());
                    // 桶键须属于所选自然日；数量完整且无重复后，才能证明零活跃日也被返回。
                    require(!date.isBefore(period.fromDate()) && date.isBefore(period.toDateExclusive()), "日期桶越界");
                    require(days.putIfAbsent(date, new DailyMetric(date, row.accessCount(), row.uniqueVisitors(),
                            row.activeUsers(), row.actualUsers(), row.newAccounts())) == null, "日期桶重复");
                }
                case HOURLY -> {
                    requireZero(row.accessCount(), row.uniqueVisitors(), row.activeUsers(), row.actualUsers(),
                            row.newAccounts(), row.loginCount(), row.uniqueUsers());
                    int hour = parseHour(row.bucket());
                    require(hours.putIfAbsent(hour, new HourlyMetric(hour, row.operationCount())) == null, "小时桶重复");
                }
                case DEVICE -> {
                    requireZero(row.accessCount(), row.uniqueVisitors(), row.activeUsers(), row.actualUsers(),
                            row.newAccounts(), row.operationCount());
                    require(row.bucket() != null && row.uniqueUsers() <= row.loginCount(), "设备桶或登录人数不合法");
                    // 保留数据库原设备代码；历史非枚举字符串不在组装阶段改写或回退为 UNKNOWN。
                    require(row.loginCount() <= previousDeviceLogins, "设备登录次数未按降序返回");
                    require(devices.putIfAbsent(row.bucket(), new DeviceMetric(row.bucket(), row.loginCount(),
                            row.uniqueUsers())) == null, "设备桶重复");
                    previousDeviceLogins = row.loginCount();
                }
                default -> throw invalid("未知聚合类型");
            }
        }
        require(summary != null, "缺少汇总行");
        require(days.size() == period.dayCount() && hours.size() == 24, "自然日或小时桶不完整");
        // 访问次数可跨日相加；账号去重汇总必须由 SQL 独立归并，不能用每日人数之和替代。
        long accessSum = 0;
        try {
            for (DailyMetric day : days.values()) accessSum = Math.addExact(accessSum, day.accessCount());
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("仪表盘聚合结果不合法：访问次数溢出", exception);
        }
        require(accessSum == summary.accessCount(), "访问汇总与每日序列不一致");
        // 同登录次数的设备由 PostgreSQL collation 排序，不能以 Java 字典序重新排列历史代码。
        return new Metrics(summary, List.copyOf(days.values()), List.copyOf(hours.values()), List.copyOf(devices.values()));
    }

    /** 验证各人数仍是活跃账号的子集，访问人数不能超过实际访问次数。 */
    private static void requireUserCounts(DashboardMetricRow row) {
        require(row.uniqueVisitors() <= row.activeUsers() && row.actualUsers() <= row.activeUsers()
                && row.uniqueVisitors() <= row.accessCount(), "账号去重计数不合法");
    }

    /** 所有内部计数均须非负，不能把空结果或类型漂移形成的负值显示成正常统计。 */
    private static void requireNonNegative(DashboardMetricRow row) {
        require(row.accessCount() >= 0 && row.uniqueVisitors() >= 0 && row.activeUsers() >= 0
                && row.actualUsers() >= 0 && row.newAccounts() >= 0 && row.operationCount() >= 0
                && row.loginCount() >= 0 && row.uniqueUsers() >= 0, "聚合计数为负数");
    }

    /** 各行种类不使用的列必须为零，及时暴露 UNION 列顺序或映射漂移。 */
    private static void requireZero(long... values) {
        for (long value : values) require(value == 0, "聚合行包含不属于其类型的计数");
    }

    /** 接受 SQL 生成的规范 ISO 自然日，不把其他格式或重复表示当作不同桶。 */
    private static LocalDate parseDay(String bucket) {
        require(bucket != null, "缺少日期桶");
        try {
            LocalDate day = LocalDate.parse(bucket);
            require(day.toString().equals(bucket), "日期桶格式不规范");
            return day;
        } catch (DateTimeException exception) {
            throw new IllegalStateException("仪表盘聚合结果不合法：日期桶格式错误", exception);
        }
    }

    /** 小时桶使用 0–23 的规范十进制文本，夏令时重复小时仍共享同一桶。 */
    private static int parseHour(String bucket) {
        require(bucket != null, "缺少小时桶");
        try {
            int hour = Integer.parseInt(bucket);
            require(hour >= 0 && hour <= 23 && Integer.toString(hour).equals(bucket), "小时桶越界或格式错误");
            return hour;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("仪表盘聚合结果不合法：小时桶格式错误", exception);
        }
    }

    /** 内部契约错误仅包含稳定原因，不拼接数据库桶内容或审计明细。 */
    private static void require(boolean valid, String reason) {
        if (!valid) throw invalid(reason);
    }

    /** 构造可由统一异常处理记录的内部错误。 */
    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("仪表盘聚合结果不合法：" + reason);
    }

    /** 原 API 的四类使用指标，列表均为不可变并已按既有规则排序。 */
    record Metrics(UsageCounts usageCounts, List<DailyMetric> dailyMetrics,
                   List<HourlyMetric> hourlyUsage, List<DeviceMetric> deviceDistribution) {
    }
}
