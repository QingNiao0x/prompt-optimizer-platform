package com.promptoptimizer.provider.domain;

/**
 * 45复用已解耦的范围与正文路径，明确上下文作为理解依据、优化正文作为执行请求的边界。
 * 完整来源仍用于内部校验，不再以原文和文件全文的拼接代替生成；旧版本保持原行为供回放。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptContextualRewriteInstructions {
    public static final String VERSION = "45";

    /** 只收敛资料的使用方式，沿用41的原任务范围、实际缺口补全和原始要求覆盖协议。 */
    public static final String ENHANCE = NaturalPromptRoutingRepairInstructions.ENHANCE + """

            项目上下文用于理解原始需求。把影响当前动作的路径、接口、字段、既有规则和必要关联融入请求，
            使执行者知道改哪里、处理什么和怎样判断完成；其余材料留作输入依据，不逐份附到优化正文。
            正文交付的是整理后的执行请求。仅当原任务确实需要时保留必要短片段，用户明确要求完整材料时遵守该范围。
            每项补充解决原请求中具体的执行缺口；已经充分的表达可以保留，不以重复原文和大量材料证明增强。
            本次没有看到某份资料，只表示本次可见范围；执行者仍可在原任务范围内检索、阅读和设计。
            原文存在影响结果的冲突时局部保留待确认边界，其余要求照常完善，不自行选定用户业务规则。
            """;

    /** 继续使用41已验证的实质影响与用户决定权检查，资料检索由原任务的执行者承担。 */
    public static final String PLAN = NaturalPromptRoutingRepairInstructions.OWNED_PLAN;

    private NaturalPromptContextualRewriteInstructions() { }
}
