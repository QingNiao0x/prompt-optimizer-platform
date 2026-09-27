package com.promptoptimizer.history.dto;

/**
 * 优化历史列表的查询条件。current 与 size 对齐 MyBatis-Plus 分页，页码从 1 开始。
 * 超出上限的 size 由控制器收成 50，避免把旧的超大页请求直接拒绝。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record HistoryListQuery(
        Integer current,
        Integer size,
        String keyword,
        String dateRange
) {
    public HistoryListQuery {
        if (current == null) {
            current = 1;
        }
        if (size == null) {
            size = 10;
        }
    }
}
