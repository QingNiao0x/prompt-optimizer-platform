package com.promptoptimizer.provider.domain;

import static com.promptoptimizer.provider.domain.NaturalPromptCoreRepairInstructions.replaceOnce;

/**
 * 自然正文的执行细节候选：依据原委托补齐必要动作、关联和完成条件。
 * 仅由服务端显式选用，复用v6准备、Plan及响应保护，旧优化记录仍按保存版本重放。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptExecutionDetailsInstructions {
    public static final String VERSION = "execution-details-v1";
    public static final String ENHANCE = enhancement();

    /** 替换v6对应的正文指导，响应协议、来源引文和范围校验继续复用。 */
    private static String enhancement() {
        String instruction = replaceOnce(NaturalPromptMaterialHandoffInstructions.ENHANCE,
                "将原始提示词完善成自然、紧凑、可直接交给执行者的请求。分别保留要做的事、怎样算完成及如何反馈。",
                "将原始提示词完善成自然、可直接交给执行者的请求。分别保留要做的事、怎样算完成及如何反馈。");
        instruction = replaceOnce(instruction, NaturalPromptCoreRepairInstructions.COMPLETION_GUIDANCE, """
                依据完整原始要求、适用上下文和实际题答，先明确处理对象、当前动作及原定结果。先写执行者需要完成的工作，再考虑用户需要的反馈。
                主动补齐原任务内影响结果的输入输出、必要步骤、环节之间的关系和可观察的完成条件。优先补容易遗漏且会造成结果不完整的环节。
                事实、现有实现和用户确认须有来源。原任务内的常规设计可提出合理做法，具体实现应结合适用材料和现有约定。需要核实的细节写成局部核查。
                补充服从用户明确规定的对象、范围、条件、例外与授权。新增独立任务、成果或交付义务须有用户依据，实施检查直接服务于确认原动作是否完成。
                明确保留用户要求的解释、复述、报告和验证证据。普通完成说明从简，实际行动须落实到原定结果。要求阅读后等待时，保留该终点。
                """);
        instruction = replaceOnce(instruction, "简单任务自然成段，独立内容需要时列点，有依赖才列步骤。", """
                信息已充分可少改或保留。需要多个相互依赖的动作时，可分段或列要点。
                压缩重复和客套，保留明确条件、关键关联和完成标准。篇幅随所需信息调整，遵守用户明确指定的字数和格式。""");
        return replaceOnce(instruction, "检查是否被文档或建议替代、是否新增行动或成果。",
                "检查原动作是否被文档或建议替代，补充是否超出委托范围，以及压缩时是否遗漏必要关联或完成条件。");
    }

    private NaturalPromptExecutionDetailsInstructions() { }
}
