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

    /**
     * 根据请求中的任务与上下文提出需要用户确认的问题；不得把问题当作已确认事实。
     *
     * @param request 已过滤敏感信息的计划输入
     * @return 供用户确认的候选问题
     */
    PlanningProviderResponse plan(PlanningProviderRequest request);
}
