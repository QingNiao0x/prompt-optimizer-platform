package com.promptoptimizer.provider.service;

import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import java.util.function.Function;

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

    /**
     * 把计划业务校验纳入模型调用；真实适配器覆盖此方法以共享解析和修复预算。
     * 旧实现保留原有应用层一次补试，仅重试校验失败，不重试网络或鉴权故障。
     */
    default <T> T planValidated(PlanningProviderRequest request,
            Function<PlanningProviderResponse, T> validation) {
        PlanningProviderResponse response = plan(request);
        try {
            return validation.apply(response);
        } catch (ProviderException failure) {
            if (failure.getFailureType() != ProviderFailureType.INVALID_RESPONSE) throw failure;
            return validation.apply(plan(request));
        }
    }
}
