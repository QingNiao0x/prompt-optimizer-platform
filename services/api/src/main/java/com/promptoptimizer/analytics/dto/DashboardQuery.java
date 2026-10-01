package com.promptoptimizer.analytics.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 管理员仪表盘的查询条件。未传 range 时按今天统计。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record DashboardQuery(
        @Size(max = 32) String range,
        @Size(max = 10, message = "fromDate 必须使用 YYYY-MM-DD 日期格式") String fromDate,
        @Size(max = 10, message = "toDate 必须使用 YYYY-MM-DD 日期格式") String toDate,
        UUID userId
) {
    public DashboardQuery {
        if (range == null || range.isBlank()) {
            range = "TODAY";
        }
    }
}
