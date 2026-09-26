package com.promptoptimizer.enhancement.dto;

import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提示词增强接口的请求模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record OptimizationRequest(
        @NotBlank(message = "原始提示词不能为空")
        @Size(max = 8_000, message = "原始提示词不能超过 8,000 个字符")
        String rawPrompt,
        @Valid ContextAnalysisRequest context,
        @Valid EnhancementOptions enhancement,
        @Size(max = 20, message = "单次最多携带 20 条会话消息")
        List<@NotNull(message = "会话消息不能为空") @Valid ConversationMessage> conversationHistory,
        @Valid PermissionPolicyInput permissionPolicy,
        @Valid PlanConfirmation planConfirmation,
        @Size(max = 160, message = "模型标识不能超过 160 个字符") String modelId
) {

    /**
     * 为可选字段提供默认值，并对列表做防御性拷贝。
     */
    public OptimizationRequest {
        context = context == null ? new ContextAnalysisRequest("", List.of()) : context;
        enhancement = enhancement == null ? EnhancementOptions.defaults() : enhancement;
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        permissionPolicy = permissionPolicy == null ? PermissionPolicyInput.empty() : permissionPolicy;
    }

    /**
     * 兼容计划模式上线前的服务内构造调用。
     */
    public OptimizationRequest(
            String rawPrompt,
            ContextAnalysisRequest context,
            EnhancementOptions enhancement,
            List<ConversationMessage> conversationHistory,
            PermissionPolicyInput permissionPolicy,
            PlanConfirmation planConfirmation
    ) {
        this(rawPrompt, context, enhancement, conversationHistory, permissionPolicy, planConfirmation, null);
    }

    public OptimizationRequest(
            String rawPrompt,
            ContextAnalysisRequest context,
            EnhancementOptions enhancement,
            List<ConversationMessage> conversationHistory,
            PermissionPolicyInput permissionPolicy
    ) {
        this(rawPrompt, context, enhancement, conversationHistory, permissionPolicy, null, null);
    }
}
