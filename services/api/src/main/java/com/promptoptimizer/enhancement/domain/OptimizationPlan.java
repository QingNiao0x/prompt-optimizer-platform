package com.promptoptimizer.enhancement.domain;

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
        long latencyMs
) {

    public OptimizationPlan {
        questions = List.copyOf(questions);
    }
}
