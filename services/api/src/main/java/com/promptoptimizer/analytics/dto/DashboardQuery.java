package com.promptoptimizer.analytics.dto;

import java.util.UUID;

/**
 * 管理员仪表盘的查询条件。未传 range 时按今天统计。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record DashboardQuery(
        String range,
        String fromDate,
        String toDate,
        UUID userId
) {
    public DashboardQuery {
        if (range == null || range.isBlank()) {
            range = "TODAY";
        }
    }
}
