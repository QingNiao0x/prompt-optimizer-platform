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
        long latencyMs,
        List<String> warnings
) {

    /**
     * 对列表字段做防御性拷贝，避免外部修改结果数据。
     */
    public OptimizationResult {
        sections = List.copyOf(sections);
        ambiguities = List.copyOf(ambiguities);
        appliedConstraints = List.copyOf(appliedConstraints);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 保存调用开始时的平台版本名称，后续管理员改名不影响本条优化记录。 */
    public OptimizationResult withModelVersion(String modelVersion) {
        return new OptimizationResult(optimizedPrompt, sections, contextReport, ambiguities, appliedConstraints,
                templateCode, new ProviderMetadata(provider.provider(), provider.model(), provider.mock(), modelVersion),
                latencyMs, warnings);
    }

    /** 兼容历史结果构造调用；旧结果没有单独的上下文警告。 */
    public OptimizationResult(
            String optimizedPrompt,
            List<PromptSection> sections,
            ContextSnapshot contextReport,
            List<String> ambiguities,
            List<String> appliedConstraints,
            TemplateCode templateCode,
            ProviderMetadata provider,
            long latencyMs
    ) {
        this(optimizedPrompt, sections, contextReport, ambiguities, appliedConstraints,
                templateCode, provider, latencyMs, List.of());
    }
}
