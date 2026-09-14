package com.promptoptimizer.enhancement.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 用户或工作区为本次增强请求补充的权限红线。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PermissionPolicyInput(
        @Size(max = 50, message = "受保护路径不能超过 50 条")
        List<@NotNull(message = "受保护路径不能为空")
                @NotBlank(message = "受保护路径不能为空")
                @Size(max = 256, message = "受保护路径不能超过 256 个字符")
                @Pattern(regexp = "[^\\r\\n]+", message = "受保护路径不能包含换行") String> protectedPaths,
        @Size(max = 50, message = "人工确认动作不能超过 50 条")
        List<@NotNull(message = "人工确认动作不能为空")
                @NotBlank(message = "人工确认动作不能为空")
                @Size(max = 64, message = "动作名称不能超过 64 个字符")
                @Pattern(regexp = "[^\\r\\n]+", message = "动作名称不能包含换行") String> requireConfirmationFor
) {

    public PermissionPolicyInput {
        protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
        requireConfirmationFor = requireConfirmationFor == null ? List.of() : List.copyOf(requireConfirmationFor);
    }

    /**
     * 返回没有额外用户规则的权限策略。
     */
    public static PermissionPolicyInput empty() {
        return new PermissionPolicyInput(List.of(), List.of());
    }
}
