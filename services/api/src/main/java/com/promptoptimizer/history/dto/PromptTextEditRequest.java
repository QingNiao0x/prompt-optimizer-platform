package com.promptoptimizer.history.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 自然正文编辑命令；expectedRevision为客户端读取的版本，不能覆盖另一页面的较新编辑。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptTextEditRequest(
        @NotBlank(message = "优化正文不能为空") @Size(max = 200_000, message = "编辑正文超出保存容量") String optimizedPrompt,
        @Min(value = 0, message = "编辑版本无效") long expectedRevision) { }
