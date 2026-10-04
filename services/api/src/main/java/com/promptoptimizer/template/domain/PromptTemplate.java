package com.promptoptimizer.template.domain;

import com.promptoptimizer.enhancement.domain.TemplateCode;

/**
 * 一个可复用的提示词增强场景模板。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptTemplate(
        TemplateCode code,
        String outputGuidance,
        String acceptanceGuidance,
        String exampleGuidance,
        TaskDeliveryProfile deliveryProfile
) {
    /** 兼容原有四参数模板；运行期细分画像由注册中心明确提供。 */
    public PromptTemplate(TemplateCode code, String outputGuidance, String acceptanceGuidance, String exampleGuidance) {
        this(code, outputGuidance, acceptanceGuidance, exampleGuidance,
                TaskIntentResolver.software(code) ? TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION : TaskDeliveryProfile.GENERAL);
    }
}
