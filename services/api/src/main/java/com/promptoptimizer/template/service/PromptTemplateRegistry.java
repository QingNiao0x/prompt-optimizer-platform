package com.promptoptimizer.template.service;

import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.enhancement.domain.TemplateCode;

/**
 * 按显式模板或原始提示词选择提示词模板。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface PromptTemplateRegistry {

    /** 优先使用调用方指定的模板，否则按原始提示词推断。 */
    PromptTemplate resolve(TemplateCode requestedTemplate, String rawPrompt);

    /** 从原始提示词推断模板代码。 */
    TemplateCode infer(String rawPrompt);
}
