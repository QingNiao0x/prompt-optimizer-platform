package com.promptoptimizer.enhancement.domain;

import java.util.List;

/**
 * 在生成最终提示词前向用户提出的一个业务问题。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanQuestion(
        String id,
        String question,
        String hint,
        PlanQuestionType type,
        List<PlanOption> options,
        List<String> examples,
        boolean allowCustomAnswer,
        @com.fasterxml.jackson.annotation.JsonIgnore PlanQuestionResponsibility responsibility
) {

    public PlanQuestion {
        options = options == null ? List.of() : List.copyOf(options);
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    /** 旧适配器、已保存会话及客户端协议继续沿用原构造方式；不伪造责任依据。 */
    public PlanQuestion(String id, String question, String hint, PlanQuestionType type,
            List<PlanOption> options, List<String> examples, boolean allowCustomAnswer) {
        this(id, question, hint, type, options, examples, allowCustomAnswer, null);
    }
}
