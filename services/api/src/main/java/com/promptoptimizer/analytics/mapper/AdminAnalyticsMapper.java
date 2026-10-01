package com.promptoptimizer.analytics.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DailyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.DeviceMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.HourlyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.MonthlyMetric;
import com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLog;
import com.promptoptimizer.analytics.dto.AnalyticsViews.UserRank;
import com.promptoptimizer.analytics.domain.AnalyticsPeriod;
import com.promptoptimizer.analytics.domain.AnalyticsAccountFilter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 通过模块化 XML 执行管理员统计聚合与审计日志分页查询。
 * 该查询属于平台级运维审计，可跨租户统计，仅由经过管理员校验的接口调用；匿名事件不计入用户指标。
 * 账号关键词由应用服务归一化，XML 以参数绑定执行，不读取密码哈希或完整审计 JSON。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface AdminAnalyticsMapper {

    /**
     * 查询匹配账号的当前存量与期间新增量；存量不受日期限制，新增量按账号创建时刻过滤。
     *
     * @param period 时区明确的左闭右开区间
     * @param account ID、有效登录邮箱和当前显示名称的组合条件，管理员账号同样计入
     * @return 当前注册账号数与区间新增账号数
     */
    AccountCounts accountCounts(@Param("period") AnalyticsPeriod period, @Param("account") AnalyticsAccountFilter account);

    /**
     * 汇总期间事件：页面访问按事件计数，访问账号按 ID 去重，活跃账号包含任一事件，实际使用限关键操作。
     *
     * @param period 事件发生时刻的左闭右开区间
     * @param account 账号组合条件；邮箱匹配使用 EXISTS，多个邮箱身份不会放大事件计数
     * @return 四项指标的区间总量，不是每日去重数的相加
     */
    UsageCounts usageCounts(@Param("period") AnalyticsPeriod period, @Param("account") AnalyticsAccountFilter account);

    /**
     * 按统计时区生成每日访问、去重访问、活跃、实际使用及新增账号序列，缺少数据的日期补零。
     * generate_series 生成日历日骨架，事件先转为本地日期再聚合，供平均日活使用完整日数作为分母。
     *
     * @param period 已通过自然日上限校验的查询范围
     * @param account 所有日桶共用的账号组合条件
     * @return 按本地日期升序排列的完整序列
     */
    List<DailyMetric> dailyMetrics(@Param("period") AnalyticsPeriod period, @Param("account") AnalyticsAccountFilter account);

    /**
     * 把期间关键操作映射到统计时区的 0–23 时，按小时跨日汇总；无操作的小时仍返回零值。
     *
     * @param period 事件发生时刻的查询范围
     * @param account 账号组合条件
     * @return 固定 24 个小时桶；夏令时重复出现的同一小时合并计数
     */
    List<HourlyMetric> hourlyUsage(@Param("period") AnalyticsPeriod period, @Param("account") AnalyticsAccountFilter account);

    /**
     * 按本地自然月汇总关键操作并补齐空月份；服务提供固定近十二个月范围，与仪表盘主时间筛选分开。
     *
     * @param period 近十二个月的左闭右开事件区间
     * @param toDateLast 含当天的最后日历日，仅用于确定月份骨架终点
     * @param account 沿用仪表盘的账号组合条件
     * @return 按月份升序排列的关键操作次数
     */
    List<MonthlyMetric> monthlyUsage(
            @Param("period") AnalyticsPeriod period,
            @Param("toDateLast") LocalDate toDateLast,
            @Param("account") AnalyticsAccountFilter account
    );

    /**
     * 仅统计 LOGIN 事件，按设备类型汇总登录次数及设备内去重账号数，缺少设备字段时归入 UNKNOWN。
     *
     * @param period 登录时刻的查询范围
     * @param account 账号组合条件
     * @return 各设备的登录分布；同一账号可出现在多个设备中，去重账号数不可跨设备相加
     */
    List<DeviceMetric> deviceDistribution(@Param("period") AnalyticsPeriod period, @Param("account") AnalyticsAccountFilter account);

    /**
     * 按关键操作次数、登录次数及账号 ID 稳定排序，只有至少一次关键操作的账号入榜。
     * 活跃天数从该账号期间全部事件计算，包含仅访问或退出的日期。
     *
     * @param period 完整的日、周或月排行周期
     * @param account 账号组合条件，不排除平台管理员
     * @param limit 应用服务已限制在 1–100 之间的最大排行条数
     * @return 每个稳定账号 ID 一行的使用频率排行
     */
    List<UserRank> usageRanking(
            @Param("period") AnalyticsPeriod period,
            @Param("account") AnalyticsAccountFilter account,
            @Param("limit") int limit
    );

    /**
     * 使用 MyBatis-Plus 分页拦截器读取审计安全字段，按发生时刻倒序及事件 ID 稳定排序。
     * 只投影必要的 IP、所在地和设备信息，不把可能包含额外内容的 details 整体返回。
     *
     * @param page 已校验的页码与单页大小，自动计数使用与列表相同的筛选条件
     * @param fromInclusive 查询开始时刻，包含该时刻
     * @param toExclusive 查询结束时刻，不包含该时刻
     * @param account 账号组合条件
     * @param eventType 已归一化的事件枚举代码，null 表示全部可查询操作
     * @return 总数与当前页安全日志字段
     */
    IPage<OperationLog> selectOperationLogs(
            @Param("page") IPage<OperationLog> page,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toExclusive") OffsetDateTime toExclusive,
            @Param("account") AnalyticsAccountFilter account,
            @Param("eventType") String eventType
    );

    /** 管理员仪表盘账户数统计的不可变结果。 */
    record AccountCounts(long registeredAccounts, long newAccounts) {
    }

    /** 管理员仪表盘使用量统计的不可变结果。 */
    record UsageCounts(long accessCount, long uniqueVisitors, long activeUsers, long actualUsers) {
    }
}
