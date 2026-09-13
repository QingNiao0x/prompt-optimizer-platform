package com.promptoptimizer.context.domain;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 定义大型文档从上传到可检索状态的处理阶段。
 */
public enum DocumentProcessingPhase {
    UPLOADING,
    QUEUED,
    EXTRACTING,
    INDEXING,
    SUMMARIZING,
    READY,
    PARTIAL,
    FAILED,
    CANCELLED
}
