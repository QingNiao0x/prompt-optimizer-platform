package com.promptoptimizer.enhancement.domain;

/**
 * 增强提示词中的一个结构化段落。
 *
 * @param type 段落类型
 * @param title 展示标题
 * @param content 段落正文
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptSection(
        PromptSectionType type,
        String title,
        String content
) {
}
