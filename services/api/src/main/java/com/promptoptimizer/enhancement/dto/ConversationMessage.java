package com.promptoptimizer.enhancement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户主动提供的当前优化会话消息。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ConversationMessage(
        @NotBlank(message = "会话角色不能为空")
        @Pattern(regexp = "user|assistant", message = "会话角色只能是 user 或 assistant")
        String role,
        @NotBlank(message = "会话内容不能为空")
        @Size(max = 4_000, message = "单条会话内容不能超过 4,000 个字符")
        String content
) {
}
