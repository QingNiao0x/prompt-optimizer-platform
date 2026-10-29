package com.promptoptimizer.provider.domain;

/**
 * 47在46的写作与覆盖协议上收敛当前有效要求，避免真实Plan确认后仍并列执行原始未决表述。
 * 原始来源和全部题答继续留在内部；仅在真实回答涉及的对象、属性和条件内更新正文要求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptEffectiveRequestInstructions {
    public static final String VERSION = "47";

    /**
     * 冻结46不回改；本候选只明确确认前后要求的生效关系，不新增分析、审阅或模型调用阶段。
     * 未回答、保留未知和普通建议均不构成更新，其他独立条件及权限边界保持原状。
     */
    public static final String ENHANCE = NaturalPromptUsefulDeliveryInstructions.ENHANCE.replace(
            "充分需求沿用有效表达，可以零扩写；保持用户指定的范围、格式、篇幅、阶段、否定、条件及例外。",
            """
            先按完整来源确定当前有效要求。actualAnswers中的真实确认，仅在同一对象、属性和适用条件内，
            更新先前未决或由用户明确修正的要求；其他独立条件仍然保留。保留未知的回答不构成新决定，
            题干、未选选项与助手建议也不构成确认。正文用一套当前有效要求指导执行，不并列写入已被确认更新的旧条件；
            原文、原问题和全部实际回答仍完整保留为内部依据，requirements合并表达当前要求并引用对应来源，
            不将保存历史依据误写成同时执行历史要求。充分需求沿用有效表达，可以零扩写；
            保持仍然适用的范围、格式、篇幅、阶段、否定、条件及例外。
            """);

    /** Plan提问和决定权沿用46；本候选仅处理确认后的正文，不扩大自动决定范围。 */
    public static final String PLAN = NaturalPromptUsefulDeliveryInstructions.PLAN;

    private NaturalPromptEffectiveRequestInstructions() { }
}
