package com.promptoptimizer.enhancement.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 生成需求确认问题的请求。
 *
 * <p>计划阶段不接收项目文件正文，只使用需求、用户主动填写的背景和短期会话。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record OptimizationPlanRequest(
        @NotBlank(message = "原始提示词不能为空")
        @Size(max = 8_000, message = "原始提示词不能超过 8,000 个字符")
        String rawPrompt,
        @Size(max = 4_000, message = "背景描述不能超过 4,000 个字符")
        String contextDescription,
        @Size(max = 20, message = "单次最多携带 20 条会话消息")
        List<@NotNull(message = "会话消息不能为空") @Valid ConversationMessage> conversationHistory
) {

    public OptimizationPlanRequest {
        contextDescription = contextDescription == null ? "" : contextDescription;
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }
}
