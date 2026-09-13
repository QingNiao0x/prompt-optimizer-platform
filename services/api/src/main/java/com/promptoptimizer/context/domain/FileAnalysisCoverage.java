package com.promptoptimizer.context.domain;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 描述单个文件从原始内容到本次模型上下文的解析和选取完整度。
 */
public record FileAnalysisCoverage(
        String path,
        String extractionStatus,
        long sourceBytes,
        long extractedCharacters,
        int indexedChunks,
        int selectedChunks,
        int selectedCharacters,
        boolean contextLimited,
        String message
) {

    public FileAnalysisCoverage {
        extractionStatus = extractionStatus == null ? "COMPLETE" : extractionStatus;
        message = message == null ? "" : message;
    }
}
