package com.promptoptimizer.context.domain;

import java.time.Instant;
import java.util.List;

/**
 * 临时文档上传、识别与索引状态；审阅阶段才提供 visionReview，pageCoverage 说明逐页处理方式与遗漏。
 * READY 表示本次处理完成，不保证视觉转录无误；PARTIAL 仍可分析，必须保留警告。
 * @author QingNiao
 * @since 0.1.0
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
        int chunkSizeBytes,
        ContextVisionModels.Review visionReview,
        List<ContextVisionModels.PageCoverage> pageCoverage
) {

    public DocumentUploadStatus {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        summary = summary == null ? "" : summary;
        errorMessage = errorMessage == null ? "" : errorMessage;
        pageCoverage = pageCoverage == null ? List.of() : List.copyOf(pageCoverage);
    }

    /** 旧上传状态和受控夹具不带视觉审阅，保留原协议字段语义。 */
    public DocumentUploadStatus(String documentId, String path, String language, DocumentProcessingPhase phase,
                                long fileSizeBytes, long uploadedBytes, int progressPercent, long extractedCharacters,
                                int chunkCount, String summary, List<String> warnings, String errorMessage,
                                Instant expiresAt, int chunkSizeBytes) {
        this(documentId, path, language, phase, fileSizeBytes, uploadedBytes, progressPercent, extractedCharacters,
                chunkCount, summary, warnings, errorMessage, expiresAt, chunkSizeBytes, null, List.of());
    }

    /** READY 与 PARTIAL 均可供分析；PARTIAL 的遗漏仍需通过警告告知用户。 */
    public boolean readyForAnalysis() {
        return phase == DocumentProcessingPhase.READY || phase == DocumentProcessingPhase.PARTIAL;
    }
}
