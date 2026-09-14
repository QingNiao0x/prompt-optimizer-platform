package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.PlanQuestion;

import java.util.List;

/**
 * Provider 返回的需求确认问题。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanningProviderResponse(
        String summary,
        List<PlanQuestion> questions,
        String provider,
        String model,
        boolean mock
) {

    public PlanningProviderResponse {
        questions = questions == null ? null : List.copyOf(questions);
    }
}
