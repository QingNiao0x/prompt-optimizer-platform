package com.promptoptimizer.history.domain;

import com.promptoptimizer.enhancement.domain.OptimizationResult;

import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 重新优化结果，同时返回新历史记录 ID 和完整增强结果。
 */
public record ReoptimizationResult(
        UUID recordId,
        OptimizationResult result
) {
}
