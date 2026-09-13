package com.promptoptimizer.context.application;

import com.promptoptimizer.context.domain.DocumentSelection;

import java.util.Optional;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 定义临时文档全文索引的查询边界，隔离上下文分析与具体存储实现。
 */
public interface DocumentIndexLookup {

    Optional<DocumentSelection> retrieve(
            String documentId,
            String query,
            int maxCharacters,
            int maxChunks
    );

    static DocumentIndexLookup empty() {
        return (documentId, query, maxCharacters, maxChunks) -> Optional.empty();
    }
}
