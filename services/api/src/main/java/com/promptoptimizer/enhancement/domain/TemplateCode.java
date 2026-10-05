package com.promptoptimizer.enhancement.domain;

/**
 * 提示词增强场景。AUTO 依据原始目标及有效确认选择指导；公开代码不等于专业方法或当前实现事实。
 * 输出细节由交付画像补充，用户明确的范围、格式与禁止事项优先于模板默认指导。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum TemplateCode {
    /** 根据用户需求自动选择模板。 */
    AUTO,
    /** 不限定行业的通用任务。 */
    GENERAL,
    /** 科研或数据分析任务。 */
    RESEARCH_ANALYSIS,
    /** 新功能开发任务。 */
    FEATURE_DEVELOPMENT,
    /** 定位并修复已有功能缺陷。 */
    BUG_FIX,
    /** 保持外部行为的前提下调整代码结构。 */
    REFACTORING,
    /** 编写或补充测试。 */
    TESTING
}
