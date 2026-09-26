package com.promptoptimizer.context.service;

import com.promptoptimizer.context.domain.DocumentSelection;

import java.util.Optional;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 定义临时文档全文索引的查询边界，隔离上下文分析与具体存储实现。
 */
public interface DocumentIndexLookup {

    /**
     * 从指定临时文档索引中选择与任务相关的片段，按字符数和分块数限制返回量。
     *
     * @return 索引不存在或无可用片段时返回空值
     */
    Optional<DocumentSelection> retrieve(
            String documentId,
            String query,
            int maxCharacters,
            int maxChunks
    );

    /** 返回不读取任何文档的实现，供未启用索引的流程安全降级。 */
    static DocumentIndexLookup empty() {
        return (documentId, query, maxCharacters, maxChunks) -> Optional.empty();
    }
}
