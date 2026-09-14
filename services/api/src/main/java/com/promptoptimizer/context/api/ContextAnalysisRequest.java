package com.promptoptimizer.context.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 上下文分析请求模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ContextAnalysisRequest(
        @Size(max = 4_000, message = "项目描述不能超过 4,000 个字符")
        String customDescription,
        @Size(max = 1_000, message = "单次最多分析 1,000 个文件")
        List<@NotNull(message = "上下文文件不能为空") @Valid ContextFileInput> files
) {

    /**
     * 文件列表为空时统一转为不可变空列表。
     */
    public ContextAnalysisRequest {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
