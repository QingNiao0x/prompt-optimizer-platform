package com.promptoptimizer.provider.domain;

/**
 * 39将范围识别与正文生成解耦，沿用第四版的正文协议与补全方式。
 * 只修正动作、回应和独立成果的边界，不引入场景路由、额外审阅调用或用户资料清单。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptRoutingRepairInstructions {
    public static final String VERSION = "39";
    /** 40进一步解除CURRENT分类对后续阶段的控制，39保留首次定向失败的历史语义。 */
    public static final String ADVISORY_TIMING_VERSION = "40";
    /** 41仅加强Plan对用户决定权的判断，正文与40逐字相同。 */
    public static final String OWNED_DECISIONS_VERSION = "41";

    /** 39起的路由修复版本均生成正文；正文与Plan按版本选择，旧31保留既有行为。 */
    public static boolean supports(String version) {
        return VERSION.equals(version) || ADVISORY_TIMING_VERSION.equals(version) || OWNED_DECISIONS_VERSION.equals(version)
                || NaturalPromptCoreRepairInstructions.VERSION.equals(version)
                || NaturalPromptContentQualityInstructions.VERSION.equals(version)
                || NaturalPromptGroundedCompletionInstructions.VERSION.equals(version)
                || NaturalPromptMultidimensionalInstructions.VERSION.equals(version)
                || NaturalPromptContextualRewriteInstructions.VERSION.equals(version)
                || NaturalPromptUsefulDeliveryInstructions.VERSION.equals(version)
                || NaturalPromptEffectiveRequestInstructions.VERSION.equals(version)
                || NaturalPromptBoundedRepairInstructions.VERSION.equals(version)
                || NaturalPromptRequirementRegistryInstructions.VERSION.equals(version)
                || NaturalPromptContextBoundaryRegistryInstructions.VERSION.equals(version)
                || NaturalPromptRegistryDiagnosticInstructions.VERSION.equals(version)
                || NaturalPromptEffectiveRegistryInstructions.VERSION.equals(version)
                || NaturalPromptRequirementsOnlyInstructions.VERSION.equals(version);
    }

    /** 准备分类继续描述回应范围；不再用该分类决定是否调用正文生成。 */
    public static final String PREPARE = NaturalPromptScopedInstructions.PREPARE;

    private static final String SCOPE = """

            taskScope只是对原始输入的范围解释，不是新增事实、授权或生成开关；以完整原始提示词和实际题答为准。
            EXPLICIT、INHERENT、UNSPECIFIED不代表需求充分程度，也不决定是否存在有用补充。
            优化当前动作的表达及必要执行关系，保持原顺序、条件、否定和完成后的等待边界；不为动作增设独立成果。
            回答、查询、解释和比较自然需要的内容可在原范围内完善；未指定独立成果时deliverable可以为空。
            原任务内的检索、阅读、梳理和已委托设计由执行者完成，不因为增强阶段未见资料而要求用户先提供全部资料。
            保留用户明确没有材料、无法访问或保留决定的事实，只让实际缺口限制依赖它的部分。
            是否需要询问用户与是否有有用补充分别判断；零个Plan问题不妨碍正文补全，充分需求仍可保持原文。
            """;

    /** 替换第四版中隐含独立作品的表达；其它业务指导和覆盖协议保持原样。 */
    public static final String ENHANCE = NaturalPromptInstructions.ENHANCE
            .replace("先确定用户要完成的动作、处理的对象和目标作品，再沿用其范围、格式、篇幅、受众及明确限制。",
                    "先确定当前动作、处理对象及原本需要的回应，再沿用其范围、格式、篇幅、受众及明确限制；动作成立不要求另有独立作品。")
            .replace("首句说明要完成什么和交付什么；", "首句说明当前要完成什么；只在原任务需要回应或成果时说明相应结果；")
            .replace("原定交付；未指定时用合适的一般表达", "原定回应或成果；未指定独立成果时可为空") + SCOPE;

    /** 保留第四版的提问影响检查；范围记录不替代完整来源，也不制造前置问题。 */
    public static final String PLAN = NaturalPromptInstructions.PLAN
            .replace("原定交付；未指定时用合适的一般表达", "原定回应或成果；未指定独立成果时可为空") + SCOPE;

    /** 结果可能不同只是必要条件；执行者可完成的取舍不因存在多个合理答案而成为用户前置义务。 */
    public static final String OWNED_PLAN = PLAN + """

            每题还必须满足决定权条件：它依赖用户独有的现实信息、业务规则、权限，或用户明确保留的决定。
            仅能说明不同答案会改变结果，不足以提问；须说明为什么该信息或决定不能由执行者在原任务内完成。
            可在原定成果中综合呈现的不同合理视角，由执行者完成比较和说明，不要求用户先选定唯一视角。
            需要检索核实的公开信息由执行者检索；原文未指定的普通执行取舍不升级为用户独有信息。
            没有满足决定权与实质影响两个条件的未知时，questions为空，继续保留可补充的执行内容。
            """;

    private NaturalPromptRoutingRepairInstructions() { }
}
