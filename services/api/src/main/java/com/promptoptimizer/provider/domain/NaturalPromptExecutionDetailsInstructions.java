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
    /**
     * 只收紧直接增强正文的任务主次、核查动作和名称依据。
     * v1、v2 按历史版本继续重放，准备、Plan 和响应协议均复用 v6。
     */
    public static final String CURRENT_TASK_VERSION = "execution-details-v2";
    /** 新请求默认增加交付对象指导与校验，继续复用 v2 的正文组织。 */
    public static final String DELIVERY_BOUNDARY_VERSION = "execution-details-v3";
    public static final String ENHANCE = enhancement();
    public static final String CURRENT_TASK_ENHANCE = currentTaskEnhancement();
    public static final String DELIVERY_BOUNDARY_ENHANCE = replaceOnce(CURRENT_TASK_ENHANCE,
            "后续打算与本轮授权分开。", PromptDeliverable.GUIDANCE + "后续打算与本轮授权分开。");

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

    /**
     * 在 v1 上替换正文组织，不改 JSON 协议。
     * 专有业务名称只留在回归输入里，不能写进所有任务共用的指导。
     */
    private static String currentTaskEnhancement() {
        String instruction = replaceOnce(ENHANCE, """
                依据完整原始要求、适用上下文和实际题答，先明确处理对象、当前动作及原定结果。先写执行者需要完成的工作，再考虑用户需要的反馈。
                主动补齐原任务内影响结果的输入输出、必要步骤、环节之间的关系和可观察的完成条件。优先补容易遗漏且会造成结果不完整的环节。
                事实、现有实现和用户确认须有来源。原任务内的常规设计可提出合理做法，具体实现应结合适用材料和现有约定。需要核实的细节写成局部核查。
                补充服从用户明确规定的对象、范围、条件、例外与授权。新增独立任务、成果或交付义务须有用户依据，实施检查直接服务于确认原动作是否完成。
                明确保留用户要求的解释、复述、报告和验证证据。普通完成说明从简，实际行动须落实到原定结果。要求阅读后等待时，保留该终点。
                """, """
                依据完整原始要求、适用上下文和实际题答，正文先写本轮要做什么、交付什么、有哪些明确边界，再写长期目标和背景细节。
                后续打算与本轮授权分开。例如后续要新增模块，而这轮只评估、给结论、不改代码，当前交付就是评估结论及其依据，不把后续实施写成本轮授权。
                本轮已经授权的修改、补测试或直接实施保持为实施，不改成只读评估。
                “先给结论”只决定答复顺序。执行者仍须查证后再判断，不能把未核查写成已证实，也不能为了先给结论而跳过必要核对。
                只补充会改变本次执行或结论的内容，并按该任务挑选检查点。询问能否复用或共用数据库表时，可引导核对主表一行代表什么、类型字段的业务含义、子记录如何关联，以及共表是否影响现有查询、查重和权限。其他任务不要套用这组检查。
                需求已经充分时少改或保留原句，不为了凑步骤追加与本次结论无关的检查。没有要求用户先回答时，执行者能自行查看的内容写成核查动作。结论取决于尚未确认的业务规则时，说明不同取值会怎样影响结论，不替用户选定。
                表名、字段名、常量和值域只有原始提示词或适用项目上下文写明时才作为事实。依据不足时，用用户已给出的模块、目录或页面指明核查入口，不借用其他对象的名称。
                用户说明了字段的业务含义时保留该含义，复用现有字段还是新增字段留给评估，不预设必须新增。用户提到子表、子项或类型时，必要时让执行者核实它指界面中的子项、业务活动类型，还是独立物理表。
                事实、现有实现和用户确认须有来源。原任务内的常规设计可提出合理做法，具体实现应结合适用材料和现有约定。需要核实的细节写成局部核查。
                补充服从用户明确规定的对象、范围、条件、例外与授权。新增独立任务、成果或交付义务须有用户依据，实施检查直接服务于确认原动作是否完成。
                明确保留用户要求的解释、复述、报告和验证证据。普通完成说明从简，实际行动须落实到原定结果。要求阅读后等待时，保留该终点。
                """);
        return replaceOnce(instruction,
                "检查原动作是否被文档或建议替代，补充是否超出委托范围，以及压缩时是否遗漏必要关联或完成条件。",
                "检查原动作是否被文档或建议替代，补充是否超出委托范围或把后续目标写成了本轮授权，名称是否缺少原文或适用上下文依据，以及压缩时是否遗漏会改变结论的关联。先给结论只检查答复顺序，不把未核查写成已证实。");
    }

    private NaturalPromptExecutionDetailsInstructions() { }
}
