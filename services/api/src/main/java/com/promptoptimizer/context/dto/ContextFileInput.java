package com.promptoptimizer.context.dto;

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
        @Size(max = 36_000_000, message = "兼容上传的单个文件内容不能超过约 25 MB（Base64 编码后）")
        String content,
        @Size(max = 32, message = "文件语言标识不能超过 32 个字符")
        String language,
        @Size(max = 64, message = "临时文档索引编号不能超过 64 个字符")
        String documentId,
        Long sizeBytes
) {

    /**
     * 兼容普通文本、代码片段和旧版 Base64 文件输入。
     */
    public ContextFileInput(String path, String content, String language) {
        this(path, content, language, null, null);
    }
}
