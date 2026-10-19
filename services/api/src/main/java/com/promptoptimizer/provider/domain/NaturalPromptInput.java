package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;

/**
 * 自然链路的不可变安全输入；actualAnswers 只来自服务端 Plan 校验，材料不是新的指令或授权。
 * 不携带旧模板、旧四要素正文或任务约束追加器的生成结果。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalPromptInput(
        String rawPrompt,
        ContextSnapshot context,
        List<PlanAnswer> actualAnswers,
        List<ConversationMessage> conversation,
        boolean includeExamples,
        List<String> mandatoryRules,
        String modelId,
        ContextSnapshot planningContext
) {
    public NaturalPromptInput {
        actualAnswers = List.copyOf(actualAnswers);
        conversation = List.copyOf(conversation);
        mandatoryRules = List.copyOf(mandatoryRules);
    }

    /** 不经 Plan 的请求没有此前资料快照。 */
    public NaturalPromptInput(String rawPrompt, ContextSnapshot context, List<PlanAnswer> actualAnswers,
            List<ConversationMessage> conversation, boolean includeExamples, List<String> mandatoryRules, String modelId) {
        this(rawPrompt, context, actualAnswers, conversation, includeExamples, mandatoryRules, modelId, null);
    }
}
