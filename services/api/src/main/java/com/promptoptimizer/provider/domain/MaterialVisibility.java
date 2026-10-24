package com.promptoptimizer.provider.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 本次优化请求的资料可见性，只由服务端根据已允许输入计算。
 * 没附文件表示优化时未见，不表示现实没有；用户明确写出的缺失或无权才标为用户缺失。
 * 模型只能读取该目录，不能改写状态或把未见材料登记成待确认事实。
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
        /** 用户原句已经写明没有、无权或不存在，只限制依赖该事实的部分。 */
        USER_ABSENT,
        /** 服务端绑定的真实题答，按原对象保留，不再打开为新的待确认。 */
        USER_OPEN,
        /** 用户自己的原话或讨论，决定目标和限制，但没有另作缺失判定。 */
        USER_STATED
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
                case BOUND_PLAN_ANSWER -> explicitAbsence(source.text()) ? State.USER_ABSENT : State.USER_OPEN;
                case ORIGINAL_REQUEST, USER_CONVERSATION -> explicitAbsence(source.text()) ? State.USER_ABSENT : State.USER_STATED;
                case ASSISTANT_CONVERSATION -> State.USER_STATED;
            }));
        }
        return new MaterialVisibility(visible ? State.VISIBLE_NOW : State.EXECUTION_ONLY, List.copyOf(labels));
    }

    /**
     * 只认用户已经写出的缺失或无权，不用“未上传”或“尚未确定”推断现实没有资料。
     * 短语保持完整，避免把普通否定或“不存在唯一答案”误标成资料缺失。
     */
    static boolean explicitAbsence(String text) {
        return text != null && (text.contains("没有相关资料") || text.contains("没有资料")
                || text.contains("无权访问") || text.contains("无法访问") || text.contains("没有权限"));
    }
}
