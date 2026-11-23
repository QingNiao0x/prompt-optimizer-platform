package com.promptoptimizer.provider.domain;

/**
 * 材料来源传递与局部记录修复的独立工程采用版本。
 * 正文及Plan逐字复用v5，运行时按版本启用已验证的来源标注和固定首稿修复。
 * 历史记录保留原版本，PROTOCOL_SCOPE_REPAIR可回退v5。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptMaterialHandoffInstructions {
    public static final String VERSION = "core-repair-v6";
    public static final String PREPARE = NaturalPromptPlanContextInstructions.PREPARE;
    public static final String ENHANCE = NaturalPromptProtocolRepairInstructions.ENHANCE;
    public static final String PLAN = NaturalPromptProtocolRepairInstructions.PLAN;

    /** 使用跨阶段材料来源编号、用户目标校验和固定正文修复的版本，共用同一传递协议。 */
    public static boolean usesMaterialHandoff(String version) {
        return VERSION.equals(version) || NaturalPromptExpressionInstructions.VERSION.equals(version)
                || NaturalPromptExecutionDetailsInstructions.VERSION.equals(version)
                || NaturalPromptProjectTaskInstructions.supports(version);
    }

    private NaturalPromptMaterialHandoffInstructions() { }
}
