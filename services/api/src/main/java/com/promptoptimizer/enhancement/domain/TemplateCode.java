package com.promptoptimizer.enhancement.domain;

/**
 * 提示词增强场景。AUTO 表示由系统依据原始需求自动选择模板。
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
