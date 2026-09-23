package com.promptoptimizer.context.application;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 表示大型文档上传、解析和索引流程中的可预期业务错误。
 */
public class DocumentUploadException extends RuntimeException {

    private final Reason reason;

    public DocumentUploadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DocumentUploadException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    /**
     * 文档上传与解析失败的稳定业务原因。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public enum Reason {
        INVALID_ARGUMENT,
        NOT_FOUND,
        CONFLICT,
        PAYLOAD_TOO_LARGE,
        CAPACITY_EXCEEDED
    }
}
