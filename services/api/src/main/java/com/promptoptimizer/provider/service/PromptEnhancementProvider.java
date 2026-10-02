package com.promptoptimizer.provider.service;

import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import java.util.function.Function;

/**
 * 提示词增强模型提供方的统一接口。
 *
 * <p>真实大模型和本地 Mock 实现都必须返回相同的结构化结果，避免业务层依赖具体供应商协议。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface PromptEnhancementProvider {

    /**
     * 调用统一模型适配层生成结构化提示词。
     */
    EnhancementProviderResponse enhance(EnhancementProviderRequest request);

    /**
     * 在模型返回前执行无副作用的业务结果校验与组装；真实适配器将其纳入同一修复预算。
     * 校验回调不得保存历史或发起外部操作，兼容实现默认只执行一次。
     */
    default <T> T enhanceValidated(EnhancementProviderRequest request,
            Function<EnhancementProviderResponse, T> validation) {
        return validation.apply(enhance(request));
    }
}
