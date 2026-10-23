package com.promptoptimizer.provider.domain;

/**
 * 在31意图边界内验证条件到行为的补全，而非另建场景模板或生成后评审阶段。
 * 只连接输入中已有的条件与受托设计，不能新增对象、事实、流程或授权；充分需求允许不补。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptRelationalInstructions {
    public static final String VERSION = "32";

    /** 仅替换第四版开放任务补全段，来源、真实题答、覆盖记录及其余指导逐字保留。 */
    public static final String ENHANCE = NaturalPromptInstructions.ENHANCE.replace(
            "开放任务补充有帮助的内容重点、对象的主要特点、实际做法和预期发现；让这些内容构成正文主体。\n",
            """
            补全优先落实已有要求之间尚未表达的操作关系，而不是列更多方面：条件成立时哪项行为改变，
            提供的材料具体用于哪项判断，输入与交付结果如何对应。只补会让执行者继续猜测或做错的关系。
            对用户已委托的设计，写清需要设计的关键内容与相互关系，具体成品仍由执行者完成；
            同一设计中的时间、人数、预算或状态条件应组合可行，局部安排不能相互占用或遗漏已有范围。
            这些要求只在原输入已有相应依赖时使用，不新增对象、功能、必经流程或独立检查报告。
            已有关系完整时不再展开；开放选择留给执行者合理设计，未知事实不替用户选值。
            """);

    private NaturalPromptRelationalInstructions() { }
}
