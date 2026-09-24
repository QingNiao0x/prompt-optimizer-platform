package com.promptoptimizer.history.application;

import com.promptoptimizer.enhancement.api.OptimizationRequest;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.history.domain.OptimizationHistoryDetail;
import com.promptoptimizer.history.domain.OptimizationHistoryPage;
import com.promptoptimizer.history.domain.ReoptimizationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @DateTime: 2026-08-14
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 无数据库模式下使用的空实现，只保证优化接口正常返回，不保存历史。
 */
@Service
@ConditionalOnProperty(prefix = "app.history", name = "enabled", havingValue = "false")
public class NoopOptimizationHistoryService implements OptimizationHistoryService {

    /**
     * 空实现不保存记录。
     */
    @Override
    public UUID save(OptimizationRequest request, OptimizationResult result) {
        return null;
    }

    /**
     * 历史功能关闭时列表直接返回空页。
     */
    @Override
    public OptimizationHistoryPage list(
            int page,
            int size,
            String keyword,
            OffsetDateTime createdFrom,
            OffsetDateTime createdToExclusive
    ) {
        return new OptimizationHistoryPage(List.of(), page, size, 0, 0);
    }

    /**
     * 历史功能关闭时不提供详情。
     */
    @Override
    public OptimizationHistoryDetail get(UUID id) {
        throw new UnsupportedOperationException("历史记录功能未启用");
    }

    /**
     * 历史功能关闭时不提供删除。
     */
    @Override
    public void delete(UUID id) {
        throw new UnsupportedOperationException("历史记录功能未启用");
    }

    /**
     * 历史功能关闭时不提供重新优化。
     */
    @Override
    public ReoptimizationResult reoptimize(UUID id) {
        throw new UnsupportedOperationException("历史记录功能未启用");
    }
}
