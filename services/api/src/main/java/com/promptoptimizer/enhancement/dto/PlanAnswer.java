package com.promptoptimizer.enhancement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户对一个计划问题给出的最终回答。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanAnswer(
        @NotBlank(message = "问题编号不能为空")
        @Size(max = 64, message = "问题编号不能超过 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "问题编号格式无效")
        String questionId,
        @NotBlank(message = "确认问题不能为空")
        @Size(max = 300, message = "确认问题不能超过 300 个字符")
        String question,
        @NotBlank(message = "问题回答不能为空")
        @Size(max = 1_500, message = "问题回答不能超过 1,500 个字符")
        String answer
) {
}
