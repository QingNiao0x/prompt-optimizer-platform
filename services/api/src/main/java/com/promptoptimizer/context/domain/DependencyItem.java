package com.promptoptimizer.context.domain;

/**
 * 项目依赖摘要。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record DependencyItem(
        String ecosystem,
        String name,
        String version,
        String source
) {
}
