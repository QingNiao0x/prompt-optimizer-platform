package com.promptoptimizer.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * 关键操作日志的查询条件。分页使用 MyBatis-Plus 的 current 与 size，页码从 1 开始。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record OperationLogQuery(
        @NotBlank String fromDate,
        @NotBlank String toDate,
        UUID userId,
        String eventType,
        @Min(1) @Max(100_000) Integer current,
        @Min(1) @Max(100) Integer size
) {
}
