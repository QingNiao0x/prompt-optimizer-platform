package com.promptoptimizer.provider.domain;

/**
 * 在同次响应中先列任务依据与拟议补充，再写自然正文；不要求推理过程或增加分析调用。
 * 解析仍按原字段及原覆盖规则，字段顺序只指导生成，不作为拒绝合法响应的条件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptPreparedInstructions {
    /** 先列现有任务记录的指导臂。 */
    public static final String GUIDANCE_VERSION = "11";
    /** 相同指导加经现有来源与覆盖校验器验证的三份完整响应示范。 */
    public static final String EXEMPLAR_VERSION = "12";

    /** 只由服务端版本分发选择，不读取用户主题来挑选示范。 */
    public static boolean supports(String version) {
        return GUIDANCE_VERSION.equals(version) || EXEMPLAR_VERSION.equals(version);
    }

    public static final String ENHANCE = NaturalPromptActionGuidance.ENHANCE + """

            本次仅增强：先用taskUnderstanding记下原有要求及拟议补充，再输出optimizedPrompt与framework。
            这是简短交付记录，不输出推理过程；字段及校验协议不变。
            requirementCoverage与additionCoverage引用准备写入最终正文的原句，正文须落实这些片段；
            不把输入原句或requirements.text误当成正文引文。最终核对一次实际正文与引文。
            当前不是生成Plan问题，即使输入有实际题答，review.planImpacts也必须为空数组。
            """;

    /** 按需加载固定且已校验的教学材料，不改变当前任务输入、来源目录和调用次数。 */
    public static String enhancementWithDemonstrations() {
        return ENHANCE + NaturalPromptDemonstrations.instruction();
    }

    private NaturalPromptPreparedInstructions() { }
}
