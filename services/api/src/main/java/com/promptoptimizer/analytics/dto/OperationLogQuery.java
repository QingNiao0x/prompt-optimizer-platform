package com.promptoptimizer.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 关键操作日志的查询条件。分页使用 MyBatis-Plus 的 current 与 size，页码从 1 开始。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record OperationLogQuery(
        @NotBlank @Size(max = 10) String fromDate,
        @NotBlank @Size(max = 10) String toDate,
        UUID userId,
        @Size(max = 64) String eventType,
        @Min(1) @Max(100_000) Integer current,
        @Min(1) @Max(100) Integer size
) {
}
