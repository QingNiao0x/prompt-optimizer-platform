package com.promptoptimizer.enhancement.domain;

/**
 * 服务端验证 Plan 绑定后生成的决定，只作为本次模型输入，不接受客户端自行声明。
 * questionId 和 question 来自服务端计划，answer 来自用户；scope 是保守用途分类，不证明项目实现。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ConfirmedPlanDecision(
        String questionId,
        String question,
        String topic,
        Scope scope,
        String answer,
        Source source
) {
    /** 决定来源仅为已通过服务端绑定校验的用户回答。 */
    public enum Source { USER_CONFIRMED }

    /** 内部构造统一标注用户确认来源，不允许模型自报来源。 */
    public ConfirmedPlanDecision(String questionId, String question, String topic, Scope scope, String answer) {
        this(questionId, question, topic, scope, answer, Source.USER_CONFIRMED);
    }

    /** 本次确认说明的是现状、目标、方案选择或仍然未决的信息。 */
    public enum Scope {
        /** 用户说明的当前情况，不能自动证明为已验证实现。 */
        CURRENT_STATE,
        /** 本次希望达成的目标，不改写当前情况。 */
        TARGET,
        /** 本次采用的方案或交付选择。 */
        CHOICE,
        /** 用户仍无法确定，继续保留具体待确认问题。 */
        UNRESOLVED
    }
}
