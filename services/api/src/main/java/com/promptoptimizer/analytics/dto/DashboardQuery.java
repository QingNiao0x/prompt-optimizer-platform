package com.promptoptimizer.analytics.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 管理员仪表盘的查询条件。未传 range 时按今天统计；账号 ID、邮箱关键词及名称关键词取交集。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record DashboardQuery(
        @Size(max = 32) String range,
        @Size(max = 10, message = "fromDate 必须使用 YYYY-MM-DD 日期格式") String fromDate,
        @Size(max = 10, message = "toDate 必须使用 YYYY-MM-DD 日期格式") String toDate,
        UUID userId,
        @Size(max = 320, message = "登录邮箱筛选最多 320 个字符") String email,
        @Size(max = 80, message = "显示名称筛选最多 80 个字符") String displayName
) {
    /** 未指定范围时沿用今天；空白邮箱及名称由应用服务统一归一化。 */
    public DashboardQuery {
        if (range == null || range.isBlank()) {
            range = "TODAY";
        }
    }
}
