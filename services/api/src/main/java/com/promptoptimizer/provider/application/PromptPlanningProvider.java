package com.promptoptimizer.provider.application;

import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;

/**
 * 负责识别真正影响结果的业务问题，并生成用户可直接回答的候选项。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface PromptPlanningProvider {

    PlanningProviderResponse plan(PlanningProviderRequest request);
}
