package com.promptoptimizer.context.dto;

import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 在 Plan Mode 前准备安全上下文摘要的请求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanningContextRequest(
        @NotBlank(message = "原始提示词不能为空")
        @Size(max = 8_000, message = "原始提示词不能超过 8,000 个字符")
        String rawPrompt,
        @Valid ContextAnalysisRequest context,
        @Valid PermissionPolicyInput permissionPolicy
) {

    public PlanningContextRequest {
        context = context == null ? new ContextAnalysisRequest("", List.of()) : context;
        permissionPolicy = permissionPolicy == null ? PermissionPolicyInput.empty() : permissionPolicy;
    }
}
