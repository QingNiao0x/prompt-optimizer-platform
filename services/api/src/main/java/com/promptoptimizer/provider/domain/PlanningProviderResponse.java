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
        boolean mock,
        @com.fasterxml.jackson.annotation.JsonIgnore NaturalTaskUnderstanding taskUnderstanding
) {

    public PlanningProviderResponse {
        questions = questions == null ? null : List.copyOf(questions);
    }

    /** 旧链和第一版自然Plan继续使用原协议；独立自然第二版额外携带仅供内部校验的理解记录。 */
    public PlanningProviderResponse(String summary, List<PlanQuestion> questions, String provider, String model, boolean mock) {
        this(summary, questions, provider, model, mock, null);
    }
}
