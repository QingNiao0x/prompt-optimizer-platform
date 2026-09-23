package com.promptoptimizer.context.domain;

import java.time.Instant;
import java.util.List;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 描述大型文档上传、解析和临时全文索引的实时状态。
 */
public record DocumentUploadStatus(
        String documentId,
        String path,
        String language,
        DocumentProcessingPhase phase,
        long fileSizeBytes,
        long uploadedBytes,
        int progressPercent,
        long extractedCharacters,
        int chunkCount,
        String summary,
        List<String> warnings,
        String errorMessage,
        Instant expiresAt,
        int chunkSizeBytes
) {

    public DocumentUploadStatus {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        summary = summary == null ? "" : summary;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /** READY 与 PARTIAL 均可供分析；PARTIAL 的遗漏仍需通过警告告知用户。 */
    public boolean readyForAnalysis() {
        return phase == DocumentProcessingPhase.READY || phase == DocumentProcessingPhase.PARTIAL;
    }
}
