package com.promptoptimizer.provider.domain;

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
        String instruction = replaceOnce(NaturalPromptPlanContextInstructions.ENHANCE, """
                先从完整原始要求与实际题答确定要完成的动作、原定回应及边界，再判断哪些表达缺口会妨碍它们。
                有效补充应解决原任务内部的执行问题：处理对象不清、材料用途不明、关键步骤或适用边界遗漏、原定结果无法核对。
                补充可细化这些内容，不能另设要向用户展示或交付的内容；“方便确认、体现理解、结果更完整”本身不是执行缺口。
                完成标准用于判断原有动作或成果是否达成，不要求另做一项成果来证明完成。普通完成确认不扩展成内容说明。
                用户已要求的解释、复述、报告或验证证据属于原有要求，完整保留并可在其范围内完善，不作为多余反馈删除。
                可能原因、方案和检查项是开展工作的线索；需要结论或行动时，不能仅列出线索就作为完成。
                """, """
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

    /** 冻结段落缺失或重复时立即失败，避免继承指导变化导致候选静默扩大修改范围。 */
    private static String replaceOnce(String instruction, String previous, String replacement) {
        int first = instruction.indexOf(previous);
        if (first < 0 || first != instruction.lastIndexOf(previous)) {
            throw new IllegalStateException("正文设计补全的冻结段缺失或不唯一");
        }
        return instruction.replace(previous, replacement);
    }

    private NaturalPromptInternalDesignInstructions() { }
}
