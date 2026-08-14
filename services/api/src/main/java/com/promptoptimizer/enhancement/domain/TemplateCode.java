package com.promptoptimizer.enhancement.domain;

/**
 * 提示词增强场景。AUTO 表示由系统依据原始需求自动选择模板。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum TemplateCode {
    AUTO,
    FEATURE_DEVELOPMENT,
    BUG_FIX,
    REFACTORING,
    TESTING
}
