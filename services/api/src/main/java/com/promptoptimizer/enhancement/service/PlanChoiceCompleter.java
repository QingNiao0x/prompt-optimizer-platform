package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;

import java.util.ArrayList;
import java.util.List;

/**
 * 整理已校验的问题，不把示例变成事实候选，也不因缺少推荐项而丢弃必要问题。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanChoiceCompleter {

    private PlanChoiceCompleter() {
    }

    /** 保留自由填写和真实选项；推荐依据只随推荐项返回。 */
    static PlanQuestion complete(PlanQuestion question) {
        List<PlanOption> options = new ArrayList<>();
        if (question.options() != null) {
            options.addAll(question.options());
        }
        boolean recommendedSeen = false;
        List<PlanOption> completed = new ArrayList<>();
        for (PlanOption option : options) {
            boolean recommended = option.recommended() && !recommendedSeen;
            if (recommended) {
                recommendedSeen = true;
            }
            completed.add(new PlanOption(
                    option.id(),
                    option.label(),
                    option.description(),
                    option.answer(),
                    recommended,
                    recommended ? option.recommendationReason() : ""
            ));
        }
        return new PlanQuestion(
                question.id(),
                question.question(),
                question.hint(),
                question.type(),
                completed,
                question.examples(),
                true
        );
    }

}
