package com.promptoptimizer.enhancement.domain;

/**
 * 一个可以直接选择的候选答案。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanOption(
        String id,
        String label,
        String description,
        String answer,
        boolean recommended,
        /** 推荐依据；空字符串表示未提供依据，非推荐项不展示。不是已经确认的事实。 */
        String recommendationReason
) {
    /** 兼容现有确定性 Provider 和旧会话中不含推荐依据的候选项。 */
    public PlanOption(String id, String label, String description, String answer, boolean recommended) {
        this(id, label, description, answer, recommended, "");
    }

    public PlanOption {
        recommendationReason = recommendationReason == null ? "" : recommendationReason.trim();
    }
}
