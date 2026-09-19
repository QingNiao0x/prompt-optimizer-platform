package com.promptoptimizer.enhancement.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 标记计划确认已经完成，并携带全部用户回答。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanConfirmation(
        @Size(max = 36, message = "计划编号不能超过 36 个字符")
        @Pattern(
                regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "计划编号格式无效"
        )
        String planId,
        @Valid PlanningContextReference planningContext,
        @Size(max = 8, message = "单次最多回答 8 个确认问题")
        List<@NotNull(message = "确认回答不能为空") @Valid PlanAnswer> answers
) {

    public PlanConfirmation {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    /**
     * 兼容 Plan Mode 会话绑定上线前的请求和历史记录。
     */
    public PlanConfirmation(List<PlanAnswer> answers) {
        this(null, null, answers);
    }
}
