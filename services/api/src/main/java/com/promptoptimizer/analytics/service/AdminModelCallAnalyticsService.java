package com.promptoptimizer.analytics.service;

import com.promptoptimizer.analytics.dto.ModelCallQuery;
import com.promptoptimizer.analytics.dto.TokenRankingQuery;
import com.promptoptimizer.analytics.dto.AnalyticsViews.ModelCallPage;
import com.promptoptimizer.analytics.dto.AnalyticsViews.TokenRankingView;

/** 平台管理员专用匿名模型对账读取端口；不提供按账号关联数字用量的能力。 */
public interface AdminModelCallAnalyticsService {
    /** 按完整自然日/周/月查询匿名来源的 Token Top N。 */
    TokenRankingView tokenRanking(TokenRankingQuery query);
    /** 按统计时区的包含日期范围查询安全尝试明细。 */
    ModelCallPage modelCalls(ModelCallQuery query);
}
