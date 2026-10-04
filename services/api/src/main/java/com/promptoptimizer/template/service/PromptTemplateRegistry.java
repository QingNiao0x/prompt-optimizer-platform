package com.promptoptimizer.template.service;

import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import java.util.List;

/**
 * 按显式模板或原始提示词选择提示词模板。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface PromptTemplateRegistry {

    /** 优先使用调用方指定的模板，否则按原始提示词推断。 */
    PromptTemplate resolve(TemplateCode requestedTemplate, String rawPrompt);

    /** 仅使用已经通过服务端绑定验证的交付决定细化模板；旧实现保持兼容。 */
    default PromptTemplate resolve(TemplateCode requestedTemplate, String rawPrompt, List<ConfirmedPlanDecision> decisions) {
        return resolve(requestedTemplate, rawPrompt);
    }

    /** 从原始提示词推断模板代码。 */
    TemplateCode infer(String rawPrompt);
}
