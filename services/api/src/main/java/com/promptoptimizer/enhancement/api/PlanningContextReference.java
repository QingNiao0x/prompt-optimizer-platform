package com.promptoptimizer.enhancement.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 引用一次已经完成并经过安全过滤的计划上下文分析。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanningContextReference(
        @NotBlank(message = "计划上下文编号不能为空")
        @Pattern(
                regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "计划上下文编号格式无效"
        )
        String contextId,
        @NotBlank(message = "计划上下文版本不能为空")
        @Size(max = 80, message = "计划上下文版本不能超过 80 个字符")
        @Pattern(regexp = "sha256:[0-9a-f]{64}", message = "计划上下文版本格式无效")
        String version
) {
}
