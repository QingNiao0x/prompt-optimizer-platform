package com.promptoptimizer.analytics.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DailyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DeviceMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.HourlyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.MonthlyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLog;
import com.promptoptimizer.analytics.dto.AnalyticsViews.UserRank;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 通过模块化 XML 执行管理员统计聚合与审计日志分页查询。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface AdminAnalyticsMapper {

    /** 统计所有账户数和所选时间范围内的新账户数。 */
    AccountCounts accountCounts(@Param("period") AnalyticsPeriod period, @Param("userId") UUID userId);

    /** 聚合访问量、去重访问账号、活跃账号和关键操作账号。 */
    UsageCounts usageCounts(@Param("period") AnalyticsPeriod period, @Param("userId") UUID userId);

    /** 按本地自然日生成完整统计序列，空闲日期也返回零值。 */
    List<DailyMetric> dailyMetrics(@Param("period") AnalyticsPeriod period, @Param("userId") UUID userId);

    /** 按本地小时统计关键操作。 */
    List<HourlyMetric> hourlyUsage(@Param("period") AnalyticsPeriod period, @Param("userId") UUID userId);

    /** 按本地自然月统计关键操作并补齐空月份。 */
    List<MonthlyMetric> monthlyUsage(
            @Param("period") AnalyticsPeriod period,
            @Param("toDateLast") LocalDate toDateLast,
            @Param("userId") UUID userId
    );

    /** 按登录设备汇总登录次数和去重账号数。 */
    List<DeviceMetric> deviceDistribution(@Param("period") AnalyticsPeriod period, @Param("userId") UUID userId);

    /** 按账号 ID 排列关键操作排行。 */
    List<UserRank> usageRanking(
            @Param("period") AnalyticsPeriod period,
            @Param("userId") UUID userId,
            @Param("limit") int limit
    );

    /** 使用 MyBatis-Plus 分页插件读取安全展示字段，不返回 JSON 明细。 */
    IPage<OperationLog> selectOperationLogs(
            @Param("page") IPage<OperationLog> page,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toExclusive") OffsetDateTime toExclusive,
            @Param("userId") UUID userId,
            @Param("eventType") String eventType
    );

    /** 管理员仪表盘账户数统计的不可变结果。 */
    record AccountCounts(long registeredAccounts, long newAccounts) {
    }

    /** 管理员仪表盘使用量统计的不可变结果。 */
    record UsageCounts(long accessCount, long uniqueVisitors, long activeUsers, long actualUsers) {
    }
}
