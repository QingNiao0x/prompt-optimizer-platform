package com.promptoptimizer.context.domain;

/**
 * 临时文档从上传、可选视觉审阅到可检索的处理阶段；PARTIAL 可检索但不代表全文完成。
 * FAILED 与 CANCELLED 不可用于增强，原件和索引按会话生命周期清理。
 * @author QingNiao
 * @since 0.1.0
 */
public enum DocumentProcessingPhase {
    UPLOADING,
    QUEUED,
    EXTRACTING,
    /** 本地检查完成，等待用户逐页审阅与外发确认；此阶段不占用账号模型名额。 */
    AWAITING_CONFIRMATION,
    INDEXING,
    SUMMARIZING,
    READY,
    PARTIAL,
    FAILED,
    CANCELLED
}
