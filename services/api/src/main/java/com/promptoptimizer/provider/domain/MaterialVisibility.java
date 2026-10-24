package com.promptoptimizer.provider.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 本次优化请求的资料可见性，只由服务端根据已允许输入计算。
 * 没附文件只表示优化时未见；目录不解释否定、条件或题答含义，不推断现实缺失或确认。
 * 来源身份来自服务端，事实状态仍须结合对应原文的对象、条件及确定程度理解。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record MaterialVisibility(State requestMaterials, List<SourceState> sources) {
    /**
     * 服务端允许的资料状态。未出现在目录中的值不得由模型自报。
     * VISIBLE_NOW 优先于 EXECUTION_ONLY：只要本次仍有允许的项目上下文，请求就不是“未见”。
     */
    public enum State {
        /** 本次请求已经附带并允许使用的项目上下文。 */
        VISIBLE_NOW,
        /** 优化时没有项目上下文；下游执行时仍可能检索或阅读，不能写成现实缺失。 */
        EXECUTION_ONLY,
        /** 服务端绑定的真实题答；回答可能是未知或部分确认，不代表参数已确定。 */
        BOUND_ANSWER,
        /** 用户自己的原话或讨论，决定目标和限制，但没有另作缺失判定。 */
        USER_STATED,
        /** 既有助手建议；不具有用户确认、事实或授权的效力。 */
        ASSISTANT_SUGGESTION
    }

    /** 一条来源的服务端状态；不重复携带原文，避免目录本身变成第二份事实。 */
    public record SourceState(String sourceId, State state) { }

    public MaterialVisibility {
        sources = List.copyOf(sources);
    }

    /** 按来源类别标注。助手文本不升成用户事实，也不因缺少项目上下文被标成用户没有。 */
    public static MaterialVisibility from(NaturalPromptInput input) {
        boolean visible = input.taskSources().stream()
                .anyMatch(source -> source.kind() == NaturalTaskSource.Kind.PROJECT_CONTEXT);
        List<SourceState> labels = new ArrayList<>();
        for (NaturalTaskSource source : input.taskSources()) {
            labels.add(new SourceState(source.id(), switch (source.kind()) {
                case PROJECT_CONTEXT -> State.VISIBLE_NOW;
                // 完整题答包含题干，不能用其中的缺失词判断回答，更不能抹去条件或否定。
                case BOUND_PLAN_ANSWER -> State.BOUND_ANSWER;
                case ORIGINAL_REQUEST, USER_CONVERSATION -> State.USER_STATED;
                case ASSISTANT_CONVERSATION -> State.ASSISTANT_SUGGESTION;
            }));
        }
        return new MaterialVisibility(visible ? State.VISIBLE_NOW : State.EXECUTION_ONLY, List.copyOf(labels));
    }

}
