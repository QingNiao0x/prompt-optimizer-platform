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

    /** 匿名来源排行；Token 合计采用十进制字符串，避免浏览器整数精度丢失。
     * 只合计已报告且有效的数字，缺失值通过覆盖计数披露；不是供应商账单估算。
     */
    public record TokenRank(String source, long callCount, long successCount, long failureCount,
                            long completeUsageCount, long partialUsageCount, long unknownUsageCount,
                            long invalidUsageCount, String inputTokens, String outputTokens, String totalTokens,
                            String cacheHitTokens, String cacheMissTokens) { }

    /** 周期为完整自然日、周或月；GLOBAL 跨租户，TENANT 显式限定一个租户。 */
    public record TokenRankingView(PeriodView period, String scope, UUID tenantId, String groupBy,
                                   List<TokenRank> ranking) { }

    /** audit_event 模型事实的安全投影；无账号、IP、正文、凭据或完整 details JSON。 */
    public record ModelCallView(UUID eventId, UUID tenantId, OffsetDateTime occurredAt,
                                String requestId, String workflowId, String operation,
                                String providerRoute, String model, int attempt, boolean mock,
                                boolean successful, String failureType, Integer upstreamStatus,
                                boolean willRetry, String transportState, String usageState, String cacheState,
                                Long inputTokens, Long outputTokens, Long totalTokens,
                                Long cacheHitTokens, Long cacheMissTokens, long durationMs) { }

    /** 模型对账明细页与 MyBatis-Plus 分页契约一致。 */
    public record ModelCallPage(PeriodView period, String scope, UUID tenantId,
                                List<ModelCallView> records, long total, long size, long current, long pages) { }
}
