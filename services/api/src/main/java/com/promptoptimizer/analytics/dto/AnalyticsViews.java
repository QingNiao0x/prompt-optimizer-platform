package com.promptoptimizer.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.promptoptimizer.common.logging.ModelCallObservation;

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
     * 仪表盘主数据及各图表序列；已停用的 accessCount、uniqueVisitorCount 兼容字段固定为零。
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
            ToolUsage toolUsage,
            List<DailyMetric> dailyMetrics,
            List<HourlyMetric> hourlyUsage,
            List<MonthlyMetric> monthlyUsage,
            List<DeviceMetric> deviceDistribution,
            List<RechargeMetric> rechargeByDay,
            boolean rechargeStatisticsAvailable
    ) {
    }

    /**
     * 登录账号的独立工具统计，沿用仪表盘时间/账号筛选；提交含失败，生成按可恢复的有效结果计。
     * 素材提交包含计划准备、确认生成及再次优化，计划问题不算最终结果；不反推历史缺失事件。
     * resultExportedCount为全部工作台的已登录复制事件，不从旧事件猜测来源工具。
     */
    public record ToolUsage(long imagePromptSubmittedCount, long imagePromptGeneratedCount,
                            long textPolishSubmittedCount, long textPolishGeneratedCount,
                            long imagePromptPlanCreatedCount, long imagePromptPlanCompletedCount,
                            long imagePromptReoptimizedCount, long resultExportedCount) {
        /** 保留旧内部调用方的构造契约；新增字段只由实际事件聚合，不从提交推断。 */
        public ToolUsage(long imagePromptSubmittedCount, long imagePromptGeneratedCount,
                         long textPolishSubmittedCount, long textPolishGeneratedCount) {
            this(imagePromptSubmittedCount, imagePromptGeneratedCount, textPolishSubmittedCount,
                    textPolishGeneratedCount, 0, 0, 0, 0);
        }
        /** 数据库应返回非负次数，映射异常不能伪装为零。 */
        public ToolUsage {
            if (imagePromptSubmittedCount < 0 || imagePromptGeneratedCount < 0
                    || textPolishSubmittedCount < 0 || textPolishGeneratedCount < 0
                    || imagePromptPlanCreatedCount < 0 || imagePromptPlanCompletedCount < 0
                    || imagePromptReoptimizedCount < 0 || resultExportedCount < 0) {
                throw new IllegalArgumentException("工具统计次数不能为负数");
            }
        }
    }

    /**
     * 单日、单周或单月使用排行。
     */
    public record RankingView(PeriodView period, List<UserRank> items) {
    }

    /**
     * 统计时区某日的账号指标及功能事件次数；直接增强按提交计，Plan 按完成计，历史通用事件不反推细分。
     * accessCount、uniqueVisitors 为停用页面统计的兼容字段，固定为零。
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
                            long invalidUsageCount, long cacheKnownUsageCount, long cacheUnknownUsageCount,
                            String inputTokens, String outputTokens, String totalTokens,
                            String cacheHitTokens, String cacheMissTokens) { }

    /** 周期为完整自然日、周或月；GLOBAL 跨租户，TENANT 显式限定一个租户。 */
    public record TokenRankingView(PeriodView period, String scope, UUID tenantId, String groupBy,
                                   List<TokenRank> ranking) { }

    /** audit_event 模型事实的安全投影；无账号、IP、正文、凭据或完整 details JSON。
     * Token 为十进制文本/null；occurredAt 为调用开始 UTC 时刻，durationMs 为毫秒。
     */
    public record ModelCallView(UUID eventId, UUID tenantId, OffsetDateTime occurredAt,
                                String requestId, String workflowId, String operation,
                                String providerRoute, String model, int attempt, boolean mock,
                                boolean successful, String failureType, Integer upstreamStatus,
                                boolean willRetry, String transportState, String usageState, String cacheState,
                                String inputTokens, String outputTokens, String totalTokens,
                                String cacheHitTokens, String cacheMissTokens, long durationMs,
                                String validationReason, String validationField) {
        /** 历史或损坏 JSON 不能借新增诊断列回显任意文本；旧记录缺省保持 null。 */
        public ModelCallView {
            validationReason = !successful && "INVALID_RESPONSE".equals(failureType)
                    ? ModelCallObservation.safeValidationReason(validationReason) : null;
            validationField = validationReason == null ? null : ModelCallObservation.safeValidationField(validationField);
        }
    }

    /** 增强与 Plan 的请求结果；同租户、请求号及操作归并，完整链路才进入成功率分母。
     * repairedRequestCount 仅指已结束且实际进行了第二次调用的请求；缺号尝试不猜测请求归属。
     */
    public record ModelCallOutcomes(long completedRequestCount, long firstSuccessCount, long repairedSuccessCount,
                                    long repairedRequestCount, long finalFailureCount, long incompleteRequestCount,
                                    long uncorrelatedAttemptCount) { }

    /** 模型对账明细页与 MyBatis-Plus 分页契约一致。 */
    public record ModelCallPage(PeriodView period, String scope, UUID tenantId,
                                List<ModelCallView> records, long total, long size, long current, long pages,
                                ModelCallOutcomes outcomes) { }
}
