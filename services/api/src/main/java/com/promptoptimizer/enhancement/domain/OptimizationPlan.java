package com.promptoptimizer.enhancement.domain;

import com.promptoptimizer.enhancement.api.PlanningContextReference;

import java.time.Instant;
import java.util.List;

/**
 * 一键增强前的需求确认计划。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record OptimizationPlan(
        String summary,
        List<PlanQuestion> questions,
        TemplateCode templateCode,
        ProviderMetadata provider,
        long latencyMs,
        String planId,
        PlanningContextReference planningContext,
        Instant expiresAt
) {

    public OptimizationPlan {
        questions = List.copyOf(questions);
    }

    /**
     * 兼容不需要会话绑定的旧调用和测试数据。
     */
    public OptimizationPlan(
            String summary,
            List<PlanQuestion> questions,
            TemplateCode templateCode,
            ProviderMetadata provider,
            long latencyMs
    ) {
        this(summary, questions, templateCode, provider, latencyMs, null, null, null);
    }
}
