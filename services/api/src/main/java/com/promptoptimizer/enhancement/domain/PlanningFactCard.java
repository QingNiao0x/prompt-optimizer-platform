package com.promptoptimizer.enhancement.domain;

/** 从本次安全上下文中提取的明确事实，来源路径与原文摘录始终绑定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanningFactCard(
        String id,
        PlanningFactCategory category,
        PlanningFactOrigin origin,
        String sourcePath,
        String evidence
) {
    public PlanningFactCard {
        id = id == null ? "" : id;
        sourcePath = sourcePath == null ? "" : sourcePath;
        evidence = evidence == null ? "" : evidence;
    }

    /** 兼容只标注资料类事实的调用方。 */
    public PlanningFactCard(String id, PlanningFactCategory category, String sourcePath, String evidence) {
        this(id, category, PlanningFactOrigin.USER_MATERIAL, sourcePath, evidence);
    }
}
