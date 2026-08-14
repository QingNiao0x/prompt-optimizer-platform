package com.promptoptimizer.provider.application;

import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;

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
}
