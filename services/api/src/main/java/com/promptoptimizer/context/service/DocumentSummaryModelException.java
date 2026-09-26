package com.promptoptimizer.context.service;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 表示文档摘要模型调用失败，供 Map-Reduce 摘要模块执行安全降级。
 */
public class DocumentSummaryModelException extends RuntimeException {

    public DocumentSummaryModelException(String message) {
        super(message);
    }

    public DocumentSummaryModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
