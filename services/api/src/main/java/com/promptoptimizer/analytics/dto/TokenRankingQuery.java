package com.promptoptimizer.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 管理员匿名 Token 排行条件；租户范围与账号筛选完全独立。 */
public record TokenRankingQuery(
        @NotBlank @Pattern(regexp = "(?i)DAY|WEEK|MONTH") String period,
        @NotBlank @Size(max = 10) String date,
        @Pattern(regexp = "(?i)GLOBAL|TENANT") String scope,
        UUID tenantId,
        @Pattern(regexp = "(?i)OPERATION|SOURCE") String groupBy,
        @Min(1) @Max(100) Integer limit
) { }
