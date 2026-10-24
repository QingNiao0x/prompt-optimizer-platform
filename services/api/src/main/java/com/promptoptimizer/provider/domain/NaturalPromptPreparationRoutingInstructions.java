package com.promptoptimizer.provider.domain;

/**
 * 自然36将动作理解与生成路由分开；准备解释不再决定能否增强，也不建立用户资料欠账。
 * 沿用阶段响应、原始来源及正文覆盖保护，不新增场景模板或模型调用阶段。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptPreparationRoutingInstructions {
    public static final String VERSION = "36";

    /** 只澄清既有范围准备的判断对象，不让输入可见性改变用户已委托的动作或成果。 */
    public static final String PREPARE = NaturalPromptScopedInstructions.PREPARE.replace(
            "只识别已有要求，不推荐额外产物，不把未指定回应当缺口。资料帮助识别对象，不能建立新授权。",
            "只识别已有要求。response描述原动作需要的回应，不评估资料是否齐全；资料不完整不使写作、分析或制作变成无需回应。"
                    + "UNSPECIFIED仅表示未要求独立成果，不表示无需增强当前动作。资料帮助识别对象，不能建立新授权。");

    private static final String PREPARATION_USE = """

            taskScope是准备阶段的候选解释，不是用户事实、授权或生成开关。最终task统一记录原输入中的动作及成果；
            依据完整原始提示词及实际题答纠正准备误判，未指定独立成果时deliverable可为空，不增加说明文档。
            inputBoundary表示增强可见范围，不代表下游资料或工具是否齐全。原任务内的检索、阅读、梳理与设计由执行者完成，
            不先枚举用户要补齐的资料；用户明确没有资料、无法访问或保留决定的条件仍原样生效。
            仅将确由用户掌握且会实质改变结果的关键决定交给Plan；可委托的执行选择不退回用户。
            确有影响执行的缺口时集中标注待确认事项或需补充材料，只限制依赖该项的内容，继续指导完成原定成果；
            不能从本次未见推断现实缺失，也不以占位清单替代正文。充分需求允许少改，其余补充须解决实际执行缺口。
            """;

    /** 复用已有阶段生成，不添加行业框架；可选组织标记不决定正文能否交付。 */
    public static final String ENHANCE = NaturalPromptStageInstructions.enhancementInstruction(
            NaturalPromptStageInstructions.OPTIONAL_FRAMEWORK_VERSION) + PREPARATION_USE;

    /** Plan复用同一任务来源与影响检查，没有用户关键决定时返回零问题。 */
    public static final String PLAN = NaturalPromptStageInstructions.planningInstruction(
            NaturalPromptStageInstructions.DIAGNOSTIC_COMPATIBILITY_VERSION) + PREPARATION_USE;

    private NaturalPromptPreparationRoutingInstructions() { }
}
