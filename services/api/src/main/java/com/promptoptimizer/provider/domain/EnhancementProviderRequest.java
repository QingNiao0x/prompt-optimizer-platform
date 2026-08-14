package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.api.EnhancementOptions;
import com.promptoptimizer.template.domain.PromptTemplate;

import java.util.List;

/**
 * 统一模型适配层接收的提示词增强请求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record EnhancementProviderRequest(
        String rawPrompt,
        ContextSnapshot context,
        PromptTemplate template,
        List<String> ambiguities,
        List<String> constraints,
        List<ConversationMessage> conversationHistory,
        EnhancementOptions options
) {

    /**
     * 对列表字段做防御性拷贝，避免外部修改请求数据。
     */
    public EnhancementProviderRequest {
        ambiguities = List.copyOf(ambiguities);
        constraints = List.copyOf(constraints);
        conversationHistory = List.copyOf(conversationHistory);
    }
}
