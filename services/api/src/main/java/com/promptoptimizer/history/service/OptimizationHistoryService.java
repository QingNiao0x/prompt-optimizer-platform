package com.promptoptimizer.history.service;

import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.history.domain.OptimizationHistoryDetail;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.domain.ReoptimizationResult;

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
     * 分页查询当前演示工作区的历史记录。
     */
    OptimizationHistoryPage list(
            int page,
            int size,
            String keyword,
            OffsetDateTime createdFrom,
            OffsetDateTime createdToExclusive
    );

    /** 保留无筛选调用方的兼容入口。 */
    default OptimizationHistoryPage list(int page, int size) {
        return list(page, size, null, null, null);
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
}
