package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PlanningKnownDecision;

import java.util.List;

/**
 * 计划 Provider 使用的最小输入，包含安全摘要与有限归属证据视图，不携带完整项目文件。
 *
 * @param sourceObjectGuidance 服务端从安全证据生成的归属视图；默认空，当前生成器上限6000字符，非客户端参数
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanningProviderRequest(
        String rawPrompt,
        String contextDescription,
        List<ConversationMessage> conversationHistory,
        PlanningContextDigest planningContext,
        String model,
        List<PlanningKnownDecision> knownDecisions,
        String sourceObjectGuidance
) {

    public PlanningProviderRequest {
        contextDescription = contextDescription == null ? "" : contextDescription;
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        knownDecisions = knownDecisions == null ? List.of() : List.copyOf(knownDecisions);
        sourceObjectGuidance = sourceObjectGuidance == null ? "" : sourceObjectGuidance;
    }

    /** 内部资料归属视图默认为空，不是客户端请求字段，也不包含完整项目原文。 */
    public PlanningProviderRequest(String rawPrompt, String contextDescription, List<ConversationMessage> conversationHistory,
                                   PlanningContextDigest planningContext, String model, List<PlanningKnownDecision> knownDecisions) {
        this(rawPrompt, contextDescription, conversationHistory, planningContext, model, knownDecisions, "");
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
