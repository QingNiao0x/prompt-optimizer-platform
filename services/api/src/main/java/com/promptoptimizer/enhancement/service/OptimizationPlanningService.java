package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;

/**
 * 根据原始提示词生成确认问题的应用服务边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface OptimizationPlanningService {

    /** 识别真正影响结果的业务问题，不接收文件正文，也不保存历史记录。 */
    OptimizationPlan plan(OptimizationPlanRequest request);
}
