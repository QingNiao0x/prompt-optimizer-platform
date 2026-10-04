package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PlanningKnownDecision;

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
        String model,
        List<PlanningKnownDecision> knownDecisions
) {

    public PlanningProviderRequest {
        contextDescription = contextDescription == null ? "" : contextDescription;
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        knownDecisions = knownDecisions == null ? List.of() : List.copyOf(knownDecisions);
    }

    /** 旧适配器保持原输入契约；服务端在 Plan 入口另行补充已定信息索引。 */
    public PlanningProviderRequest(String rawPrompt, String contextDescription, List<ConversationMessage> conversationHistory,
                                   PlanningContextDigest planningContext, String model) {
        this(rawPrompt, contextDescription, conversationHistory, planningContext, model, List.of());
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
