package com.promptoptimizer.enhancement.domain;

/** 计划阶段可核验的事实维度，用于避免重复提问并标注证据类型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum PlanningFactCategory {
    REGION,
    AUDIENCE,
    JURISDICTION,
    DATA_SOURCE,
    DATA_FORMAT,
    DISEASE_CATEGORY,
    POPULATION,
    ANALYSIS_TOOL,
    ANALYSIS_METHOD,
    OUTPUT_FORMAT,
    ACCEPTANCE_CRITERIA,
    TIME_RANGE,
    BUSINESS_RULE
}
