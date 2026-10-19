package com.promptoptimizer.provider.service;

import com.promptoptimizer.provider.domain.NaturalPromptDraft;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import java.util.function.Function;

/**
 * 自然生成与其 Plan 提问的独立接口；JSON 只是传输协议，不要求四要素正文。
 * 校验回调无副作用，修复、失败和成功共享同一调用预算；历史由应用层成功后保存一次。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface NaturalPromptProvider {
    /** 理解任务并直接生成自然正文，不经过旧增强 Provider 或段落组装器。 */
    <T> T enhance(NaturalPromptInput input, Function<NaturalPromptDraft, T> validation);

    /** 只提出影响任务、事实、授权或交付的重要未知；充分需求可以零问题。 */
    <T> T plan(NaturalPromptInput input, Function<PlanningProviderResponse, T> validation);
}
