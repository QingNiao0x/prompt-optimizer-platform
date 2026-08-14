package com.promptoptimizer.history.domain;

import java.util.List;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 历史记录分页结果，避免直接把 Spring Data 的 Page 结构暴露给前端。
 */
public record OptimizationHistoryPage(
        List<OptimizationHistorySummary> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {

    /**
     * 对列表字段做防御性拷贝。
     */
    public OptimizationHistoryPage {
        items = List.copyOf(items);
    }
}
