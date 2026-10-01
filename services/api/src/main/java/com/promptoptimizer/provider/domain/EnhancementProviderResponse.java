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
        boolean mock,
        List<String> ambiguities,
        List<AmbiguityReference> ambiguityReferences
) {

    /**
     * 对结构段落做防御性拷贝，避免外部修改响应数据。
     */
    public EnhancementProviderResponse {
        sections = List.copyOf(sections);
        // null 表示旧 Provider 未提供判断；空列表表示已分析且没有歧义，二者不能混用。
        ambiguities = ambiguities == null ? null
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(ambiguities));
        ambiguityReferences = ambiguityReferences == null ? List.of()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(ambiguityReferences));
    }

    /** 兼容返回纯文本提醒的 Provider；没有关联时由应用层保守匹配已有问题。 */
    public EnhancementProviderResponse(List<PromptSection> sections, String provider, String model,
                                       boolean mock, List<String> ambiguities) {
        this(sections, provider, model, mock, ambiguities, List.of());
    }

    public EnhancementProviderResponse(List<PromptSection> sections, String provider, String model, boolean mock) {
        this(sections, provider, model, mock, null);
    }
}
