package com.promptoptimizer.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * 使用频率排行的查询条件。limit 为空时由服务按 20 条截取。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record UsageRankingQuery(
        @NotBlank String period,
        @NotBlank String date,
        UUID userId,
        @Min(1) @Max(100) Integer limit
) {
}
