package com.promptoptimizer.context.domain;

import java.util.List;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 表示从全文临时索引中为当前任务选出的文档上下文及覆盖范围。
 */
public record DocumentSelection(
        String path,
        String language,
        String content,
        String summary,
        long sourceBytes,
        long extractedCharacters,
        int totalChunks,
        int selectedChunks,
        int selectedCharacters,
        boolean completelyParsed,
        List<String> warnings
) {

    public DocumentSelection {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
