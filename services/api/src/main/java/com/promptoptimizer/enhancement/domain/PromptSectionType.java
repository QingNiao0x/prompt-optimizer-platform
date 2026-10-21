package com.promptoptimizer.enhancement.domain;

/**
 * 结构化提示词的标准段落类型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum PromptSectionType {
    /** 独立的适用背景事实；没有新增信息时保留空内部槽位。 */
    BACKGROUND,
    /** 必须存在的可执行任务，可同时自然表达交付与条件，不能留空。 */
    TASK,
    /** 尚未在任务中说明的交付形式与范围；无独立信息时可以留空。 */
    OUTPUT,
    /** 尚未在适用动作附近说明的约束；平台保护仍在组装时核对补入。 */
    CONSTRAINTS,
    /** 已归并的真正未决事项；没有事项时不生成该可选段落。 */
    CLARIFICATIONS,
    /** 具有独立检查价值的验收；没有内容时不生成该可选段落。 */
    ACCEPTANCE,
    /** 保留用户明示的示例内容和旧历史段落；不由开关或平台自动补入。 */
    EXAMPLES;

    /**
     * 四要素中只有辅助槽位允许无独有正文；缺失字段、空任务和空可选段落仍由协议拒绝。
     * 留空不授权丢失用户要求，组装器继续校验来源、保真、安全和重要未决条件。
     */
    public boolean allowsEmptyContent() {
        return this == BACKGROUND || this == OUTPUT || this == CONSTRAINTS;
    }
}
