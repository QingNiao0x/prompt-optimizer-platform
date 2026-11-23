package com.promptoptimizer.provider.domain;

import static com.promptoptimizer.provider.domain.NaturalPromptCoreRepairInstructions.replaceOnce;

/**
 * 显式对照原任务内部设计补全的正文候选，范围准备与Plan继续使用v4协议。
 * 独立版本保留旧历史与v4指导原件，候选采用取决于后续实际作品验证。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptInternalDesignInstructions {
    public static final String VERSION = "internal-design-v1";
    public static final String ENHANCE = enhancement();

    /** 仅替换正文中补全与复核的对应表达，避免相反指导同时存在。 */
    private static String enhancement() {
        String instruction = replaceOnce(NaturalPromptPlanContextInstructions.ENHANCE,
                NaturalPromptCoreRepairInstructions.COMPLETION_GUIDANCE, """
                先依据完整原始要求、实际题答和适用上下文，保留当前动作、原定回应及明确边界。
                允许完善原动作或成果内部的具体内容、方法、必要步骤、输入输出和适用边界，使表达达到可以执行的程度。
                用户委托的普通设计可在增强时依据原任务和适用上下文补入具体可执行的拟议方案，保留合理替代空间。
                拟议方案不能写成已确认事实。用户保留的重要决定仍保持未决。
                补充须说明它解决原任务的哪个执行问题，不得擅自增加另一项独立成果、替换原动作或扩大授权。
                完成标准服务于原动作或成果，用户明确要求的解释、复述、报告和验证证据按原范围保留。充分需求保留有效表达，允许不扩写。
                原因假设、方案和检查项应推动原定结论或行动，不能仅列出线索就作为完成。
                """);
        return replaceOnce(instruction, "检查是否被文档或建议替代、是否新增行动或成果。",
                "检查原任务是否被文档或建议替代，以及是否擅增另一项独立成果或扩大授权。");
    }

    private NaturalPromptInternalDesignInstructions() { }
}
