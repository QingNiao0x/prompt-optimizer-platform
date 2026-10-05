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
        List<String> warnings,
        List<PlanningFactCard> evidenceCards
) {

    /**
     * 对列表字段做防御性拷贝，避免外部修改结果数据。
     */
    public OptimizationResult {
        sections = List.copyOf(sections);
        ambiguities = List.copyOf(ambiguities);
        appliedConstraints = List.copyOf(appliedConstraints);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        evidenceCards = evidenceCards == null ? List.of() : List.copyOf(evidenceCards);
    }

    /** 保存调用开始时的平台版本名称，后续管理员改名不影响本条优化记录。 */
    public OptimizationResult withModelVersion(String modelVersion) {
        return new OptimizationResult(optimizedPrompt, sections, contextReport, ambiguities, appliedConstraints,
                templateCode, new ProviderMetadata(provider.provider(), provider.model(), provider.mock(), modelVersion),
                latencyMs, warnings, evidenceCards);
    }

    /** 汇总模型重试、上下文和结果组装的完整耗时；不改变优化正文或历史字段契约。 */
    public OptimizationResult withLatencyMs(long totalLatencyMs) {
        return new OptimizationResult(optimizedPrompt, sections, contextReport, ambiguities, appliedConstraints,
                templateCode, provider, Math.max(0, totalLatencyMs), warnings, evidenceCards);
    }

    /** 旧记录没有溯源卡片时返回空列表，保留既有结果构造和历史反序列化契约。 */
    public OptimizationResult(String optimizedPrompt, List<PromptSection> sections, ContextSnapshot contextReport,
                              List<String> ambiguities, List<String> appliedConstraints, TemplateCode templateCode,
                              ProviderMetadata provider, long latencyMs, List<String> warnings) {
        this(optimizedPrompt, sections, contextReport, ambiguities, appliedConstraints, templateCode,
                provider, latencyMs, warnings, List.of());
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
