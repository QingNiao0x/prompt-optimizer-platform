package com.promptoptimizer.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 管理统计与运维审计接口的只读响应模型，不包含密码、令牌、原始请求头或任意 JSON 明细。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class AnalyticsViews {

    private AnalyticsViews() {
    }

    /**
     * 统计区间的日历与数据库查询边界。
     */
    public record PeriodView(
            LocalDate fromDate,
            LocalDate toDateInclusive,
            OffsetDateTime fromInclusive,
            OffsetDateTime toExclusive,
            String zoneId
    ) {
    }

    /**
     * 仪表盘主数据及各图表序列。
     */
    public record DashboardView(
            PeriodView period,
            long registeredAccountCount,
            long newAccountCount,
            long actualUserCount,
            long accessCount,
            long uniqueVisitorCount,
            long activeUserCount,
            BigDecimal averageDailyActiveUsers,
            long directEnhancementCount,
            long planCompletedCount,
            List<DailyMetric> dailyMetrics,
            List<HourlyMetric> hourlyUsage,
            List<MonthlyMetric> monthlyUsage,
            List<DeviceMetric> deviceDistribution,
            List<RechargeMetric> rechargeByDay,
            boolean rechargeStatisticsAvailable
    ) {
    }

    /**
     * 单日、单周或单月使用排行。
     */
    public record RankingView(PeriodView period, List<UserRank> items) {
    }

    /**
     * 统计时区某日的账号指标及功能事件次数；直接增强按提交计，Plan 按完成计，历史通用事件不反推细分。
     */
    public record DailyMetric(
            LocalDate date,
            long accessCount,
            long uniqueVisitors,
            long activeUsers,
            long actualUsers,
            long newAccounts,
            long directEnhancementCount,
            long planCompletedCount
    ) {
    }

    /**
     * 某个本地小时发生的关键使用操作数。
     */
    public record HourlyMetric(int hour, long operationCount) {
    }

    /**
     * 某月发生的关键使用操作数。
     */
    public record MonthlyMetric(String month, long operationCount) {
    }

    /**
     * 登录设备类别、登录次数和去重账号数。
     */
    public record DeviceMetric(String deviceType, long loginCount, long uniqueUsers) {
    }

    /**
     * 按账号 ID 汇总的使用频率排行。
     */
    public record UserRank(
            UUID userId,
            String displayName,
            long operationCount,
            long loginCount,
            long activeDays
    ) {
    }

    /**
     * 已支付充值记录的套餐日汇总；金额使用最小货币单位。
     */
    public record RechargeMetric(
            LocalDate date,
            String planCode,
            String planName,
            long paidCount,
            long amountMinor,
            String currency
    ) {
    }

    /**
     * 单条关键操作日志的安全展示字段。
     */
    public record OperationLog(
            UUID eventId,
            UUID userId,
            String displayName,
            String eventType,
            OffsetDateTime occurredAt,
            String clientIp,
            String country,
            String province,
            String city,
            String loginCountry,
            String loginProvince,
            String loginCity,
            String deviceType
    ) {
    }

    /**
     * 操作日志分页响应，字段与 MyBatis-Plus 分页结果对齐。
     */
    public record OperationLogPage(
            List<OperationLog> records,
            long total,
            long size,
            long current,
            long pages
    ) {
    }
}
