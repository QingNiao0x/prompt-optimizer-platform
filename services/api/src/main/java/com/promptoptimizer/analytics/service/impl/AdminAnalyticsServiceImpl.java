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
import com.promptoptimizer.analytics.domain.AnalyticsAccountFilter;
import com.promptoptimizer.analytics.mapper.AdminAnalyticsMapper;
import com.promptoptimizer.payment.mapper.RechargeRecordMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

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

    /** 装配时间解析与可选数据库组件；不启用充值时不要求存在支付记录数据源。 */
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

    /**
     * 在同一只读快照中生成平台管理员仪表盘，避免并发事件导致总量与每日序列前后不一致。
     * 主指标使用所选区间，月份趋势固定为截至今天的近十二个月；两者共用账号筛选。
     *
     * @param query 已通过接口校验的日期及账号条件，服务继续校验日期边界和关键词长度
     * @return 包含零日的统计序列、账号与使用指标及充值数据源可用标记
     * @throws InvalidOptimizationRequestException 日期区间或关键词不合法时抛出
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardView dashboard(DashboardQuery query) {
        String range = query.range();
        String fromDate = query.fromDate();
        String toDate = query.toDate();
        AnalyticsAccountFilter account = new AnalyticsAccountFilter(query.userId(), query.email(), query.displayName());
        AnalyticsPeriod period = periodResolver.resolve(range, fromDate, toDate);
        AdminAnalyticsMapper mapper = requireAnalyticsMapper();
        AdminAnalyticsMapper.AccountCounts accountCounts = mapper.accountCounts(period, account);
        // 主区间的四类事件指标共享一次数据库扫描；严格组装完整桶，不回退为空或伪造零值。
        var metrics = DashboardMetricsAssembler.assemble(period, mapper.dashboardMetrics(period, account));
        AdminAnalyticsMapper.UsageCounts usageCounts = metrics.usageCounts();
        var dailyMetrics = metrics.dailyMetrics();
        AnalyticsPeriod twelveMonthPeriod = lastTwelveMonths();
        // 区间去重活跃人数不能代替每日人数之和；分母包括无事件日，也不使用小时数推算自然日。
        BigDecimal dailyActiveSum = dailyMetrics.stream().map(metric -> BigDecimal.valueOf(metric.activeUsers()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageDailyActive = dailyActiveSum
                .divide(BigDecimal.valueOf(period.dayCount()), 2, RoundingMode.HALF_UP);
        // 尚未接入支付数据源时明确返回不可用，不查询预留表，也不把空数组伪装成充值统计已完成。
        var rechargeByDay = rechargeStatisticsEnabled
                ? requireRechargeMapper().paidByDay(period, account)
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
                metrics.featureUsage().directEnhancementCount(),
                metrics.featureUsage().planCompletedCount(),
                dailyMetrics,
                metrics.hourlyUsage(),
                mapper.monthlyUsage(twelveMonthPeriod,
                        twelveMonthPeriod.toDateExclusive().minusDays(1), account),
                metrics.deviceDistribution(),
                rechargeByDay,
                rechargeStatisticsEnabled
        );
    }

    /**
     * 查询日期锚点所在的完整日、周或月排行，支持与仪表盘一致的账号组合筛选。
     *
     * @param query 周期、锚点日期、账号条件及可选排行条数
     * @return 已展开的实际排行日期与按关键操作次数排序的账号
     * @throws InvalidOptimizationRequestException 周期、日期、关键词或排行条数不合法时抛出
     */
    @Transactional(readOnly = true)
    public RankingView usageRanking(UsageRankingQuery query) {
        String periodValue = query.period();
        String dateValue = query.date();
        AnalyticsAccountFilter account = new AnalyticsAccountFilter(query.userId(), query.email(), query.displayName());
        Integer limit = query.limit();
        int normalizedLimit = limit == null ? 20 : limit;
        if (normalizedLimit < 1 || normalizedLimit > 100) {
            throw new InvalidOptimizationRequestException("排行条数必须在 1 到 100 之间");
        }
        AnalyticsPeriod period = periodResolver.resolveRanking(periodValue, dateValue);
        return new RankingView(periodView(period),
                requireAnalyticsMapper().usageRanking(period, account, normalizedLimit));
    }

    /**
     * 按期间、账号条件及已识别事件类型分页查询关键操作审计，沿用统计时区构造边界。
     *
     * @param query 包含开始/结束日、账号、事件类型及从 1 开始的分页条件
     * @return 当前页日志与相同筛选条件下的总条数
     * @throws InvalidOptimizationRequestException 日期、关键词、事件类型或分页越界时抛出
     */
    @Transactional(readOnly = true)
    public OperationLogPage operationLogs(OperationLogQuery query) {
        String fromDate = query.fromDate();
        String toDate = query.toDate();
        AnalyticsAccountFilter account = new AnalyticsAccountFilter(query.userId(), query.email(), query.displayName());
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
        // 显式计数复用日志筛选片段；不对完整明细排序/投影再 COUNT，避免大数据分页浪费内存。
        page.setCountId("countOperationLogs");
        IPage<com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLog> result =
                requireAnalyticsMapper().selectOperationLogs(page,
                        period.fromInclusive(), period.toExclusive(), account, normalizedEventType);
        return new OperationLogPage(result.getRecords(), result.getTotal(), result.getSize(),
                result.getCurrent(), result.getPages());
    }

    /**
     * 验证并转换事件类型代码；空白表示不限定操作，非法代码在访问数据库前返回参数错误。
     *
     * @param eventType 事件类型代码
     * @return 规范化的枚举代码，未指定时为 null
     * @throws InvalidOptimizationRequestException 输入不属于可查询事件枚举时抛出
     */
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

    /** 可选持久化组件未装配时明确报告不可用，避免空指针或伪造零值统计。 */
    private AdminAnalyticsMapper requireAnalyticsMapper() {
        if (analyticsMapper == null) {
            throw new IllegalStateException("统计数据库当前不可用");
        }
        return analyticsMapper;
    }

    /** 仅在显式启用充值统计后获取支付 Mapper；启用配置与持久化装配不一致时报告错误。 */
    private RechargeRecordMapper requireRechargeMapper() {
        if (rechargeMapper == null) {
            throw new IllegalStateException("充值统计数据库当前不可用");
        }
        return rechargeMapper;
    }

    /** 将数据库排他结束日还原为界面包含的结束日，同时返回准确时刻和统计时区。 */
    private PeriodView periodView(AnalyticsPeriod period) {
        return new PeriodView(
                period.fromDate(), period.toDateExclusive().minusDays(1),
                period.fromInclusive(), period.toExclusive(), period.zoneId().getId()
        );
    }

    /** 月份趋势固定从十一月前的月初统计到今天结束，不跟随主查询的自定义起止日期。 */
    private AnalyticsPeriod lastTwelveMonths() {
        AnalyticsPeriod today = periodResolver.resolve("TODAY", null, null);
        LocalDate from = today.fromDate().withDayOfMonth(1).minusMonths(11);
        // 用统计时区的月初午夜确定起点，不能用固定 365 天代替十二个自然月。
        OffsetDateTime fromInclusive = from.atStartOfDay(today.zoneId()).toOffsetDateTime();
        return new AnalyticsPeriod(from, today.toDateExclusive(), today.zoneId(),
                fromInclusive, today.toExclusive());
    }
}
