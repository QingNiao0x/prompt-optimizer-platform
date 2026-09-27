package com.promptoptimizer.history.domain;

import java.util.List;

/**
 * 历史列表分页结果，字段与 MyBatis-Plus 的 current、size、records、total、pages 对齐。
 * 不直接返回 Page，避免把分页插件的内部开关序列化到接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record OptimizationHistoryPage(
        List<OptimizationHistorySummary> records,
        long total,
        long size,
        long current,
        long pages
) {

    /**
     * 对列表字段做防御性拷贝。
     */
    public OptimizationHistoryPage {
        records = List.copyOf(records);
    }
}
