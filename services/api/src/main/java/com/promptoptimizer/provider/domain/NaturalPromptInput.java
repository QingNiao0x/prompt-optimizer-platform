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
        List<String> mandatoryRules,
        String modelId,
        ContextSnapshot planningContext,
        String strategyVersion
) {
    public NaturalPromptInput {
        actualAnswers = List.copyOf(actualAnswers);
        conversation = List.copyOf(conversation);
        mandatoryRules = List.copyOf(mandatoryRules);
        strategyVersion = strategyVersion == null ? NaturalPromptInstructions.VERSION : strategyVersion;
    }

    /** 来源编号由服务端计算；模型只可引用，不可改写来源类别或把资料提升为新授权。 */
    @com.fasterxml.jackson.annotation.JsonProperty("taskSources")
    public List<NaturalTaskSource> taskSources() {
        return NaturalTaskSource.from(this);
    }

    /** 只有服务端历史分发与受控评测可选择兼容版本；原文及来源保持不变。 */
    public NaturalPromptInput withStrategyVersion(String version) {
        return new NaturalPromptInput(rawPrompt, context, actualAnswers, conversation,
                mandatoryRules, modelId, planningContext, version);
    }

    /** 兼容调用方；新请求使用当前策略，旧历史必须显式传回原版本。 */
    public NaturalPromptInput(String rawPrompt, ContextSnapshot context, List<PlanAnswer> actualAnswers,
            List<ConversationMessage> conversation, List<String> mandatoryRules,
            String modelId, ContextSnapshot planningContext) {
        this(rawPrompt, context, actualAnswers, conversation, mandatoryRules, modelId, planningContext, null);
    }

    /** 不经 Plan 的请求没有此前资料快照。 */
    public NaturalPromptInput(String rawPrompt, ContextSnapshot context, List<PlanAnswer> actualAnswers,
            List<ConversationMessage> conversation, List<String> mandatoryRules, String modelId) {
        this(rawPrompt, context, actualAnswers, conversation, mandatoryRules, modelId, null);
    }
}
