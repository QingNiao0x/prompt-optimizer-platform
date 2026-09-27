package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.service.AdminAnalyticsService;
import com.promptoptimizer.analytics.dto.DashboardQuery;
import com.promptoptimizer.analytics.dto.OperationLogQuery;
import com.promptoptimizer.analytics.dto.UsageRankingQuery;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DashboardView;
import com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLogPage;
import com.promptoptimizer.analytics.dto.AnalyticsViews.PeriodView;
import com.promptoptimizer.analytics.dto.AnalyticsViews.RankingView;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper;
import com.promptoptimizer.payment.mapper.RechargeRecordMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 编排管理员统计读取、业务范围验证和指标口径；不缓存可永久查询的审计明细。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class AdminAnalyticsServiceImpl implements AdminAnalyticsService {

    private final AnalyticsPeriodResolver periodResolver;
    private final AdminAnalyticsMapper analyticsMapper;
    private final RechargeRecordMapper rechargeMapper;
    private final boolean rechargeStatisticsEnabled;

    public AdminAnalyticsServiceImpl(
            AnalyticsPeriodResolver periodResolver,
            ObjectProvider<AdminAnalyticsMapper> analyticsMapperProvider,
            ObjectProvider<RechargeRecordMapper> rechargeMapperProvider,
            @Value("${app.analytics.recharge.enabled:false}") boolean rechargeStatisticsEnabled
    ) {
        this.periodResolver = periodResolver;
        this.analyticsMapper = analyticsMapperProvider.getIfAvailable();
        this.rechargeMapper = rechargeMapperProvider.getIfAvailable();
        this.rechargeStatisticsEnabled = rechargeStatisticsEnabled;
    }

    /** 汇总仪表盘指标；日活平均值包含所选范围内无行为的自然日。 */
    @Transactional(readOnly = true)
    public DashboardView dashboard(DashboardQuery query) {
        String range = query.range();
        String fromDate = query.fromDate();
        String toDate = query.toDate();
        UUID userId = query.userId();
        AnalyticsPeriod period = periodResolver.resolve(range, fromDate, toDate);
        AdminAnalyticsMapper mapper = requireAnalyticsMapper();
        AdminAnalyticsMapper.AccountCounts accountCounts = mapper.accountCounts(period, userId);
        AdminAnalyticsMapper.UsageCounts usageCounts = mapper.usageCounts(period, userId);
        var dailyMetrics = mapper.dailyMetrics(period, userId);
        AnalyticsPeriod twelveMonthPeriod = lastTwelveMonths();
        long dailyActiveSum = dailyMetrics.stream().mapToLong(metric -> metric.activeUsers()).sum();
        BigDecimal averageDailyActive = BigDecimal.valueOf(dailyActiveSum)
                .divide(BigDecimal.valueOf(period.dayCount()), 2, RoundingMode.HALF_UP);
        var rechargeByDay = rechargeStatisticsEnabled
                ? requireRechargeMapper().paidByDay(period, userId)
                : List.<com.promptoptimizer.analytics.dto.AnalyticsViews.RechargeMetric>of();
        return new DashboardView(
                periodView(period),
                accountCounts.registeredAccounts(),
                accountCounts.newAccounts(),
                usageCounts.actualUsers(),
                usageCounts.accessCount(),
                usageCounts.uniqueVisitors(),
                usageCounts.activeUsers(),
                averageDailyActive,
                dailyMetrics,
                mapper.hourlyUsage(period, userId),
                mapper.monthlyUsage(twelveMonthPeriod,
                        twelveMonthPeriod.toDateExclusive().minusDays(1), userId),
                mapper.deviceDistribution(period, userId),
                rechargeByDay,
                rechargeStatisticsEnabled
        );
    }

    /** 查询以指定日期为锚点的日、周或月使用频率排行。 */
    @Transactional(readOnly = true)
    public RankingView usageRanking(UsageRankingQuery query) {
        String periodValue = query.period();
        String dateValue = query.date();
        UUID userId = query.userId();
        Integer limit = query.limit();
        int normalizedLimit = limit == null ? 20 : limit;
        if (normalizedLimit < 1 || normalizedLimit > 100) {
            throw new InvalidOptimizationRequestException("排行条数必须在 1 到 100 之间");
        }
        AnalyticsPeriod period = periodResolver.resolveRanking(periodValue, dateValue);
        return new RankingView(periodView(period),
                requireAnalyticsMapper().usageRanking(period, userId, normalizedLimit));
    }

    /** 分页查询审计关键操作；只允许可识别事件代码作为过滤器。 */
    @Transactional(readOnly = true)
    public OperationLogPage operationLogs(OperationLogQuery query) {
        String fromDate = query.fromDate();
        String toDate = query.toDate();
        UUID userId = query.userId();
        String eventType = query.eventType();
        Integer current = query.current();
        Integer size = query.size();
        AnalyticsPeriod period = periodResolver.resolve("CUSTOM", fromDate, toDate);
        int normalizedCurrent = current == null ? 1 : current;
        int normalizedSize = size == null ? 10 : size;
        if (normalizedCurrent < 1 || normalizedCurrent > 100_000) {
            throw new InvalidOptimizationRequestException("日志页码必须在 1 到 100000 之间");
        }
        if (normalizedSize < 1 || normalizedSize > 100) {
            throw new InvalidOptimizationRequestException("每页日志数量必须在 1 到 100 之间");
        }
        String normalizedEventType = normalizeEventType(eventType);
        Page<com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLog> page =
                new Page<>(normalizedCurrent, normalizedSize);
        page.setOptimizeCountSql(false);
        IPage<com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLog> result =
                requireAnalyticsMapper().selectOperationLogs(page,
                        period.fromInclusive(), period.toExclusive(), userId, normalizedEventType);
        return new OperationLogPage(result.getRecords(), result.getTotal(), result.getSize(),
                result.getCurrent(), result.getPages());
    }

    private String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return null;
        }
        try {
            return AnalyticsEventType.valueOf(eventType.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw new InvalidOptimizationRequestException("eventType 不是可查询的关键操作代码");
        }
    }

    private AdminAnalyticsMapper requireAnalyticsMapper() {
        if (analyticsMapper == null) {
            throw new IllegalStateException("统计数据库当前不可用");
        }
        return analyticsMapper;
    }

    private RechargeRecordMapper requireRechargeMapper() {
        if (rechargeMapper == null) {
            throw new IllegalStateException("充值统计数据库当前不可用");
        }
        return rechargeMapper;
    }

    private PeriodView periodView(AnalyticsPeriod period) {
        return new PeriodView(
                period.fromDate(), period.toDateExclusive().minusDays(1),
                period.fromInclusive(), period.toExclusive(), period.zoneId().getId()
        );
    }

    private AnalyticsPeriod lastTwelveMonths() {
        AnalyticsPeriod today = periodResolver.resolve("TODAY", null, null);
        LocalDate from = today.fromDate().withDayOfMonth(1).minusMonths(11);
        OffsetDateTime fromInclusive = from.atStartOfDay(today.zoneId()).toOffsetDateTime();
        return new AnalyticsPeriod(from, today.toDateExclusive(), today.zoneId(),
                fromInclusive, today.toExclusive());
    }
}
