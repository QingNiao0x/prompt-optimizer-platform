package com.promptoptimizer.history.service;

import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.history.domain.OptimizationHistoryDetail;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.domain.ReoptimizationResult;
import com.promptoptimizer.history.domain.DocumentReoptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptDocument;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 优化历史服务接口，提供列表、详情、删除、保存和重新优化能力。
 */
public interface OptimizationHistoryService {

    /**
     * 分页查询当前工作区的历史记录。current 从 1 开始，与 MyBatis-Plus Page 一致。
     */
    OptimizationHistoryPage list(
            int current,
            int size,
            String keyword,
            OffsetDateTime createdFrom,
            OffsetDateTime createdToExclusive
    );

    /** 保留无筛选调用方的兼容入口。 */
    default OptimizationHistoryPage list(int current, int size) {
        return list(current, size, null, null, null);
    }

    /**
     * 查询单条历史记录详情。
     */
    OptimizationHistoryDetail get(UUID id);

    /** 逻辑删除当前可访问的历史记录，使其从后续列表、详情和再次优化查询中不可见。 */
    void delete(UUID id);

    /**
     * 保存一次优化结果，返回新记录 ID。
     */
    UUID save(OptimizationRequest request, OptimizationResult result);

    /**
     * 使用历史记录中的原始输入再次优化，并保存为新记录。
     */
    ReoptimizationResult reoptimize(UUID id);

    /** V2 在同一范围和分页口径内读取新旧记录，旧实现保持原行为。 */
    default OptimizationHistoryPage listAll(int current, int size, String keyword,
            OffsetDateTime createdFrom, OffsetDateTime createdToExclusive) {
        return list(current, size, keyword, createdFrom, createdToExclusive);
    }

    /** V2 读取自然正文或旧段落详情，不重新渲染保存的正文。 */
    default OptimizationHistoryDetail getDocument(UUID id) { return get(id); }

    /** 保存V2原生结果；未实现的持久化适配器必须明确拒绝，不能丢失正文。 */
    default UUID saveDocument(OptimizationRequest request, PromptDocument result) {
        throw new UnsupportedOperationException("当前历史适配器不支持V2结果");
    }

    /** V2 再次增强按已保存链路分发；旧适配器仍可返回旧链结果。 */
    default DocumentReoptimizationResult reoptimizeDocument(UUID id) {
        var result = reoptimize(id);
        return new DocumentReoptimizationResult(result.recordId(), PromptDocument.legacy(result.result()));
    }

    /** 只编辑当前用户范围内的自然结果；保留初始生成正文，并执行版本及服务端保护校验。 */
    default com.promptoptimizer.history.domain.PromptTextEditResult editText(UUID id,
            com.promptoptimizer.history.dto.PromptTextEditRequest request) {
        throw new UnsupportedOperationException("当前历史适配器不支持自然正文编辑");
    }
}
