package com.promptoptimizer.context.service;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 保存语义向量索引的运行参数，避免业务模块直接依赖基础设施配置类。
 */
public record SemanticVectorIndexOptions(
        boolean enabled,
        int batchSize,
        int maxBatchCharacters
) {

    public SemanticVectorIndexOptions {
        if (batchSize < 1) {
            throw new IllegalArgumentException("向量批次大小必须大于 0");
        }
        if (maxBatchCharacters < 1) {
            throw new IllegalArgumentException("向量批次字符上限必须大于 0");
        }
    }
}
