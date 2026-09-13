package com.promptoptimizer.context.domain;

import java.util.List;

/**
 * 一次上下文分析的完整结果，只包含截断和脱敏后的内容。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ContextSnapshot(
        String customDescription,
        List<TechnologyStackItem> technologyStack,
        List<DependencyItem> dependencies,
        List<String> directoryTree,
        List<FileSnippet> fileSnippets,
        String analysisStatus,
        List<FileAnalysisCoverage> fileCoverage,
        List<String> warnings,
        List<String> redactions,
        String analysisVersion
) {

    public ContextSnapshot {
        technologyStack = List.copyOf(technologyStack);
        dependencies = List.copyOf(dependencies);
        directoryTree = List.copyOf(directoryTree);
        fileSnippets = List.copyOf(fileSnippets);
        analysisStatus = analysisStatus == null ? "COMPLETE" : analysisStatus;
        fileCoverage = fileCoverage == null ? List.of() : List.copyOf(fileCoverage);
        warnings = List.copyOf(warnings);
        redactions = List.copyOf(redactions);
    }

    /**
     * 兼容已有调用方和旧测试数据；没有完整度信息时按既有成功结果处理。
     */
    public ContextSnapshot(
            String customDescription,
            List<TechnologyStackItem> technologyStack,
            List<DependencyItem> dependencies,
            List<String> directoryTree,
            List<FileSnippet> fileSnippets,
            List<String> warnings,
            List<String> redactions,
            String analysisVersion
    ) {
        this(
                customDescription,
                technologyStack,
                dependencies,
                directoryTree,
                fileSnippets,
                warnings.isEmpty() ? "COMPLETE" : "PARTIAL",
                List.of(),
                warnings,
                redactions,
                analysisVersion
        );
    }
}
