package com.promptoptimizer.context.service;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 保存 Map-Reduce 全文摘要的批次、输出长度和模型调用保护参数。
 */
public record MapReduceSummaryOptions(
        boolean enabled,
        int mapBatchSize,
        int reduceBatchSize,
        int maxBatchCharacters,
        int intermediateSummaryCharacters,
        int finalSummaryCharacters,
        int maxMapCalls,
        int maxReduceCalls
) {

    public MapReduceSummaryOptions {
        if (mapBatchSize < 1 || reduceBatchSize < 2) {
            throw new IllegalArgumentException("摘要批次大小配置无效");
        }
        if (maxBatchCharacters < 1) {
            throw new IllegalArgumentException("摘要批次字符上限必须大于 0");
        }
        if (intermediateSummaryCharacters < 1 || finalSummaryCharacters < 1) {
            throw new IllegalArgumentException("摘要输出字符上限必须大于 0");
        }
        if (maxMapCalls < 1 || maxReduceCalls < 1) {
            throw new IllegalArgumentException("摘要模型调用上限必须大于 0");
        }
    }
}
