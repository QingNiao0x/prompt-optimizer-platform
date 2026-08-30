package com.promptoptimizer.context.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户主动选择的单个文件输入。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ContextFileInput(
        @NotBlank(message = "文件路径不能为空")
        @Size(max = 512, message = "文件路径不能超过 512 个字符")
        String path,
        @Size(max = 1_500_000, message = "单个文件内容不能超过约 1 MB（Base64 编码后）")
        String content,
        @Size(max = 32, message = "文件语言标识不能超过 32 个字符")
        String language
) {
}
