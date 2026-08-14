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
        String exampleGuidance
) {
}
