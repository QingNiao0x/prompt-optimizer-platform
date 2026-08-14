package com.promptoptimizer.context.domain;

/**
 * 识别到的技术栈条目。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record TechnologyStackItem(
        String name,
        String source,
        double confidence
) {
}
