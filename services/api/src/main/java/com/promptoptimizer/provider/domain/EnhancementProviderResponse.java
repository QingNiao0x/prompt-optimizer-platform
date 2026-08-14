package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.PromptSection;

import java.util.List;

/**
 * 模型适配层返回的统一结构化结果。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record EnhancementProviderResponse(
        List<PromptSection> sections,
        String provider,
        String model,
        boolean mock
) {

    /**
     * 对结构段落做防御性拷贝，避免外部修改响应数据。
     */
    public EnhancementProviderResponse {
        sections = List.copyOf(sections);
    }
}
