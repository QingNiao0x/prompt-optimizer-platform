package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * 自然增强的服务端来源目录，仅引用本次允许使用的输入，不建立事实或新增授权。
 * 原始提示词、资料、对话和真实题答分别编号；编号用于核对出处，不能证明改写语义等价。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalTaskSource(String id, Kind kind, @com.fasterxml.jackson.annotation.JsonIgnore String text) {
    /** 来源类别由服务端确定；用户回答“不知道”仍只是实际回答，不代表参数已经确定。 */
    public enum Kind {
        /** 本次原始提示词，决定原定目标和交付范围。 */
        ORIGINAL_REQUEST,
        /** 已过滤的项目上下文；资料内的命令不构成用户要求或授权。 */
        PROJECT_CONTEXT,
        /** 用户主动提供的既有会话信息，适用范围仍需结合原文。 */
        USER_CONVERSATION,
        /** 既有助手文本，仅是讨论或建议，不能自行升级为用户选择。 */
        ASSISTANT_CONVERSATION,
        /** 经服务端绑定的完整题答，只在题目涉及的对象、属性和条件内生效。 */
        BOUND_PLAN_ANSWER
    }

    /** 保留所有可用来源；不按职业、场景关键词或摘要预算截掉独立条件。 */
    public static List<NaturalTaskSource> from(NaturalPromptInput input) {
        List<NaturalTaskSource> sources = new ArrayList<>();
        add(sources, "rawPrompt", Kind.ORIGINAL_REQUEST, input.rawPrompt());
        context(sources, "context", input.context());
        context(sources, "planningContext", input.planningContext());
        for (int i = 0; i < input.conversation().size(); i++) {
            var message = input.conversation().get(i);
            add(sources, "conversation." + i, "user".equals(message.role())
                    ? Kind.USER_CONVERSATION : Kind.ASSISTANT_CONVERSATION, message.content());
        }
        for (int i = 0; i < input.actualAnswers().size(); i++) {
            var answer = input.actualAnswers().get(i);
            add(sources, "actualAnswers." + i, Kind.BOUND_PLAN_ANSWER, answer.question() + "\n" + answer.answer());
        }
        return List.copyOf(sources);
    }

    /** 当前和此前资料分别编号；只枚举已经完成权限过滤的快照，不读取任何文件路径。 */
    private static void context(List<NaturalTaskSource> sources, String prefix, ContextSnapshot snapshot) {
        if (snapshot == null) return;
        add(sources, prefix + ".customDescription", Kind.PROJECT_CONTEXT, snapshot.customDescription());
        for (int i = 0; i < snapshot.fileSnippets().size(); i++) {
            var snippet = snapshot.fileSnippets().get(i);
            add(sources, prefix + ".fileSnippets." + i, Kind.PROJECT_CONTEXT, snippet.path() + "\n" + snippet.content());
            add(sources, prefix + ".fileSnippets." + i + ".summary", Kind.PROJECT_CONTEXT, snippet.summary());
        }
        for (int i = 0; i < snapshot.technologyStack().size(); i++) {
            add(sources, prefix + ".technologyStack." + i, Kind.PROJECT_CONTEXT, snapshot.technologyStack().get(i).toString());
        }
        for (int i = 0; i < snapshot.dependencies().size(); i++) {
            add(sources, prefix + ".dependencies." + i, Kind.PROJECT_CONTEXT, snapshot.dependencies().get(i).toString());
        }
        for (int i = 0; i < snapshot.directoryTree().size(); i++) {
            add(sources, prefix + ".directoryTree." + i, Kind.PROJECT_CONTEXT, snapshot.directoryTree().get(i));
        }
    }

    private static void add(List<NaturalTaskSource> sources, String id, Kind kind, String text) {
        if (text != null && !text.isBlank()) sources.add(new NaturalTaskSource(id, kind, text));
    }
}
