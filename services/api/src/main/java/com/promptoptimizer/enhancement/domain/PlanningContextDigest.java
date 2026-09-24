package com.promptoptimizer.enhancement.domain;

import java.util.List;

/**
 * 发送给计划模型的轻量上下文。它只包含经过分析和裁剪的事实，不包含文件正文。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanningContextDigest(
        String description,
        List<String> technologies,
        List<String> dependencies,
        List<String> directoryOverview,
        List<String> fileSummaries,
        String analysisStatus,
        int analyzedFileCount,
        List<String> warnings,
        List<PlanningFactCard> factCards
) {

    public PlanningContextDigest {
        description = description == null ? "" : description;
        technologies = technologies == null ? List.of() : List.copyOf(technologies);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        directoryOverview = directoryOverview == null ? List.of() : List.copyOf(directoryOverview);
        fileSummaries = fileSummaries == null ? List.of() : List.copyOf(fileSummaries);
        analysisStatus = analysisStatus == null ? "EMPTY" : analysisStatus;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        factCards = factCards == null ? List.of() : List.copyOf(factCards);
    }

    /** 兼容未包含事实卡片的历史调用方。 */
    public PlanningContextDigest(
            String description,
            List<String> technologies,
            List<String> dependencies,
            List<String> directoryOverview,
            List<String> fileSummaries,
            String analysisStatus,
            int analyzedFileCount,
            List<String> warnings
    ) {
        this(description, technologies, dependencies, directoryOverview, fileSummaries,
                analysisStatus, analyzedFileCount, warnings, List.of());
    }
}
