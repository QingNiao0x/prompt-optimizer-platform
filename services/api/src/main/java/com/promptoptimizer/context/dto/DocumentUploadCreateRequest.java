package com.promptoptimizer.context.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建临时文档分片上传任务；visionRequested 只开启本地审阅，不代表同意向模型外发。
 * 旧客户端省略该字段时继续采用本地解析链路。
 * @author QingNiao
 * @since 0.1.0
 */
public record DocumentUploadCreateRequest(
        @NotBlank(message = "文件路径不能为空")
        @Size(max = 512, message = "文件路径不能超过 512 个字符")
        String path,
        @NotBlank(message = "文件类型不能为空")
        @Size(max = 32, message = "文件类型不能超过 32 个字符")
        String language,
        @Min(value = 1, message = "文件大小必须大于 0")
        @Max(value = 52_428_800, message = "单个大型文档不能超过 50 MB")
        long sizeBytes,
        boolean visionRequested
) {
    /** 兼容旧客户端和本地文本解析调用；省略视觉审阅不产生任何新增外发授权。 */
    public DocumentUploadCreateRequest(String path, String language, long sizeBytes) {
        this(path, language, sizeBytes, false);
    }
}
