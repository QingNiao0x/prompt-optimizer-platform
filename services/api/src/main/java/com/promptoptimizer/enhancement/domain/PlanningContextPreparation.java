package com.promptoptimizer.enhancement.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;

import java.time.Instant;

/**
 * Plan Mode 可以引用的短期上下文分析结果。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanningContextPreparation(
        String contextId,
        String version,
        PlanningContextDigest digest,
        ContextSnapshot contextReport,
        Instant expiresAt,
        long latencyMs
) {
}
