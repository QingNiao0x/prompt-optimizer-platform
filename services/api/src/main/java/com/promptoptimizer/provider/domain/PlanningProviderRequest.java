package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;

import java.util.List;

/**
 * 计划 Provider 使用的最小输入，不包含项目文件正文。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanningProviderRequest(
        String rawPrompt,
        String contextDescription,
        List<ConversationMessage> conversationHistory,
        PlanningContextDigest planningContext,
        String model
) {

    public PlanningProviderRequest {
        contextDescription = contextDescription == null ? "" : contextDescription;
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }

    public PlanningProviderRequest(
            String rawPrompt,
            String contextDescription,
            List<ConversationMessage> conversationHistory,
            PlanningContextDigest planningContext
    ) {
        this(rawPrompt, contextDescription, conversationHistory, planningContext, null);
    }

    public PlanningProviderRequest(
            String rawPrompt,
            String contextDescription,
            List<ConversationMessage> conversationHistory
    ) {
        this(rawPrompt, contextDescription, conversationHistory, null, null);
    }
}
