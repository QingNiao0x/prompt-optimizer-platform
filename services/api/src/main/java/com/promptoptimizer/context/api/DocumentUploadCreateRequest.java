package com.promptoptimizer.context.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 定义大型文档分片上传任务的创建参数。
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
        long sizeBytes
) {
}
