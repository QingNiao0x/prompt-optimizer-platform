package com.promptoptimizer.enhancement.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;

import java.util.List;

/**
 * 一次提示词增强的完整结果。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record OptimizationResult(
        String optimizedPrompt,
        List<PromptSection> sections,
        ContextSnapshot contextReport,
        List<String> ambiguities,
        List<String> appliedConstraints,
        TemplateCode templateCode,
        ProviderMetadata provider,
        long latencyMs
) {

    /**
     * 对列表字段做防御性拷贝，避免外部修改结果数据。
     */
    public OptimizationResult {
        sections = List.copyOf(sections);
        ambiguities = List.copyOf(ambiguities);
        appliedConstraints = List.copyOf(appliedConstraints);
    }
}
