package com.promptoptimizer.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 匿名模型尝试明细查询；不会接受账号、邮箱或名称筛选。 */
public record ModelCallQuery(
        @NotBlank @Size(max = 10) String fromDate,
        @NotBlank @Size(max = 10) String toDate,
        @Pattern(regexp = "(?i)GLOBAL|TENANT") String scope,
        UUID tenantId,
        @Pattern(regexp = "prompt\\.optimize|plan\\.generate|document\\.summary\\.(map|reduce)|context\\.embedding|unknown") String operation,
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String requestId,
        @Min(1) @Max(100000) Integer current,
        @Min(1) @Max(100) Integer size
) { }
