package com.promptoptimizer.history.dto;

import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 按意见修改历史正文；原始需求、上一稿、权限和Plan题答由服务端恢复，不接受客户端替换。
 * context只接受当前主动选用的材料，继续经过既有权限过滤；历史不持久化源码。
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptRevisionRequest(
        @NotBlank @Size(max = 4000) String feedback,
        @Min(0) long expectedRevision,
        @Valid ContextAnalysisRequest context) { }
