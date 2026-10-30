package com.promptoptimizer.provider.domain;

/**
 * 可独立验收的自然链修复；v1冻结41的行为，v2消除动作、完成条件与反馈形式之间的指导冲突。
 * 两版共用既有范围识别、正文/Plan及来源覆盖协议，不新增领域模板或模型调用阶段。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptCoreRepairInstructions {
    public static final String VERSION = "core-repair-v1";

    /** 独立保存本轮动作边界指导，避免旧历史或已冻结v1重放时静默换用新语义。 */
    public static final String ACTION_BOUNDARY_VERSION = "core-repair-v2";

    /**
     * 准备仍只解释委托范围。将完成工作与反馈形式分开，不把模型的解释变成新的用户授权。
     * 条件、否定与实际题答保留在原有actions/response中，完整原输入继续传入后续阶段。
     */
    public static final String PREPARE = replaceOnce(NaturalPromptScopedInstructions.PREPARE, """
            单独确定对外回应：EXPLICIT为用户明确指定的回答或成果；INHERENT为回答原问题自然必要的结果；
            UNSPECIFIED表示未要求独立成果，description为空。动作有价值并不要求增加可供检查的报告或清单。
            """, """
            actions记录用户要做的事、对象与完成条件，保留动作顺序和适用条件；工作完成与说明工作是不同职责。
            response只描述原本要求的反馈或成果：EXPLICIT为明确指定，INHERENT为完成原动作自然必要的回应；
            UNSPECIFIED表示未要求独立成果，description为空。必要反馈不自动成为独立报告、方案或清单。
            已委托的实际行动不能缩成解释或建议，仅要求判断或建议也不扩大成执行；明确要求的文档成果继续保留。
            """);

    /** 只替换引发成果推定的表达，仍复用同一来源、题答、补全及正文覆盖协议。 */
    public static final String ENHANCE = enhancement();

    /** Plan使用同一动作边界，保留原有决定权和结果影响两个提问条件。 */
    public static final String PLAN = replaceOnce(NaturalPromptRoutingRepairInstructions.OWNED_PLAN, """
            使用与直接增强相同的原定目标、交付物、完整来源和信息状态，判断哪些重要决定确实属于用户。
            """, """
            使用与直接增强相同的当前动作、完成条件、反馈范围和完整来源，判断哪些重要决定确实属于用户。
            已委托行动不降级为建议或文档，仅要求分析不升级为执行；用户明确要求的文档仍是原定成果。
            未指定独立文档不构成交付缺口；仍按用户独有信息或保留决定、实质影响结果两个条件判断是否澄清。
            """);

    /** 两个显式版本均可重放，默认采用由服务端配置另行决定。 */
    public static boolean supports(String version) {
        return VERSION.equals(version) || ACTION_BOUNDARY_VERSION.equals(version);
    }

    /**
     * 采用替换而非继续叠加相反指令；共同动作边界适用于所有任务，不按职业、路径或关键词路由。
     * taskScope是可核对的解释，不能覆盖完整原文和真实回答，也不能直接证明最终语义正确。
     */
    private static String enhancement() {
        String source = replaceOnce(NaturalPromptRoutingRepairInstructions.ENHANCE, """
                将用户的原始提示词完善成一份自然、紧凑、可直接交给执行者的请求，目标作品仍由执行者完成。
                先确定当前动作、处理对象及原本需要的回应，再沿用其范围、格式、篇幅、受众及明确限制；动作成立不要求另有独立作品。
                """, """
                将原始提示词完善成自然、紧凑、可直接交给执行者的请求。分别保留要做的事、怎样算完成及如何反馈。
                当前动作与完成条件决定任务终点；反馈只是说明实际结果，不能用文档、建议或计划替代已委托的行动。
                仅要求判断或建议时不扩大为执行；用户明确要求文档时保留其成果类型、范围、格式和篇幅。
                """);
        source = replaceOnce(source, """
                开放任务补充有帮助的内容重点、对象的主要特点、实际做法和预期发现；让这些内容构成正文主体。
                """, """
                补充直接服务原动作：处理什么、材料怎样用于判断或操作、哪些步骤有必要、用什么结果检查完成。
                可能原因、方案和检查项是开展工作的线索；需要结论或行动时，不能仅列出线索就作为完成。
                """);
        source = replaceOnce(source,
                "\"goal\":\"原定目标\",\"deliverable\":\"原定回应或成果；未指定独立成果时可为空\"",
                "\"goal\":\"当前动作、对象及完成条件，保留顺序和条件\",\"deliverable\":\"原定反馈或成果；无独立成果时可为空\"");
        return replaceOnce(source, """
                返回前对照原始提示词、全部实际回答和适用上下文，检查目标与对象是否偏移、原要求是否遗漏或改变，
                条件是否矛盾、交付是否变成空模板，以及同一要求是否无意义重复；发现问题先修正正文，再填写最终覆盖引用。
                """, """
                返回前逐项对照原始动作、完成条件、否定及实际题答，并与taskScope核对；准备解释冲突时以完整原输入为准。
                用现有requirements和正文覆盖引用核对每个独立动作及条件，检查是否被文档或建议替代、是否新增行动或成果。
                修正目标偏移、条件矛盾、空模板及重复后再填写覆盖记录；来源和引文匹配本身不证明语义相同。
                """);
    }

    /** 冻结指导的替换目标必须唯一，防止上游文字变动后局部修复静默失效。 */
    private static String replaceOnce(String source, String original, String replacement) {
        int first = source.indexOf(original);
        if (first < 0 || first != source.lastIndexOf(original)) {
            throw new IllegalStateException("动作边界指导的冻结段缺失或不唯一");
        }
        return source.replace(original, replacement);
    }

    private NaturalPromptCoreRepairInstructions() { }
}
