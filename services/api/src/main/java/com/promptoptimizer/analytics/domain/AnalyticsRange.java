package com.promptoptimizer.analytics.domain;

/**
 * 管理统计支持的日历范围；日期边界按配置时区计算。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum AnalyticsRange {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    CUSTOM
}
