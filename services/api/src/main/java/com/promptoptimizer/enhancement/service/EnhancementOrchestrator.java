package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.domain.OptimizationResult;

/**
 * 编排一次完整的提示词增强流程。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface EnhancementOrchestrator {

    /**
     * 执行一次提示词增强并返回完整结果。
     */
    OptimizationResult optimize(OptimizationRequest request);
}
