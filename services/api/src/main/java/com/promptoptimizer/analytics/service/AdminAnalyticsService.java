package com.promptoptimizer.analytics.service;

import com.promptoptimizer.analytics.dto.AnalyticsViews.DashboardView;
import com.promptoptimizer.analytics.dto.AnalyticsViews.OperationLogPage;
import com.promptoptimizer.analytics.dto.AnalyticsViews.RankingView;
import com.promptoptimizer.analytics.dto.DashboardQuery;
import com.promptoptimizer.analytics.dto.OperationLogQuery;
import com.promptoptimizer.analytics.dto.UsageRankingQuery;

/**
 * 管理员统计与审计查询的应用服务边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface AdminAnalyticsService {

    /** 汇总仪表盘指标；日活平均值包含所选范围内无行为的自然日。 */
    DashboardView dashboard(DashboardQuery query);

    /** 查询以指定日期为锚点的日、周或月使用频率排行。 */
    RankingView usageRanking(UsageRankingQuery query);

    /** 分页查询审计关键操作；只允许可识别事件代码作为过滤器。 */
    OperationLogPage operationLogs(OperationLogQuery query);
}
