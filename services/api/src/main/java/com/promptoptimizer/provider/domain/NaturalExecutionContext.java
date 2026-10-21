package com.promptoptimizer.provider.domain;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 服务端生成的本次操作阶段，只传绑定回答编号，不复写答案、原文、身份或文件路径。
 * 收齐Plan回答不等于每个参数确定或获得权限；实际含义仍由完整题答逐项决定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalExecutionContext(Phase phase, List<String> receivedAnswerSourceIds) {
    public NaturalExecutionContext { receivedAnswerSourceIds = List.copyOf(receivedAnswerSourceIds); }

    /** 区分出题与生成；零问题Plan没有未回答题目，与直接生成同样可继续任务。 */
    public enum Phase {
        /** 尚在判断哪些重要信息需要询问。 */ PLANNING,
        /** 没有实际题答的生成，不虚构确认记录。 */ GENERATE_DIRECT,
        /** 本次真实Plan题答已通过服务端绑定；未知、拒答和独立条件仍须保留。 */ GENERATE_AFTER_ANSWERS
    }

    /** 只根据本次服务端操作和经过绑定的回答生成阶段，不从材料文字推断确认或授权。 */
    public static NaturalExecutionContext from(NaturalPromptInput input, boolean planning) {
        Phase phase = planning ? Phase.PLANNING : input.actualAnswers().isEmpty()
                ? Phase.GENERATE_DIRECT : Phase.GENERATE_AFTER_ANSWERS;
        return new NaturalExecutionContext(phase, IntStream.range(0, input.actualAnswers().size())
                .mapToObj(index -> "actualAnswers." + index).toList());
    }
}
