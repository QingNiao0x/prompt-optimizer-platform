package com.promptoptimizer.provider.domain;

/**
 * 在同一次自然生成中分别理解当前动作、对外结果和后续条件的候选指导。
 * 复用阶段协议与完整来源，不以场景词表推断成果，也不要求新增模型分析阶段或重复意图表。
 * 任务记录的 goal 承载当前动作，deliverable 只承载有依据的对外结果；空结果不等于任务不完整。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptActionContractInstructions {
    public static final String VERSION = "27";
    public static final String ENHANCE = enhancement();
    public static final String PLAN = planning();

    /** 使用已拆分的阶段协议，替换原先默认要求目标作品的生成步骤；第四版及旧候选字节保持不变。 */
    private static String enhancement() {
        String source = NaturalPromptStageInstructions.enhancementInstruction(
                NaturalPromptStageInstructions.DIAGNOSTIC_COMPATIBILITY_VERSION);
        source = replace(source, """
                将用户的原始提示词完善成一份自然、紧凑、可直接交给执行者的请求，目标作品仍由执行者完成。
                先确定用户要完成的动作、处理的对象和目标作品，再沿用其范围、格式、篇幅、受众及明确限制。
                把已提供的具体信息直接用于相应动作与交付要求；背景只用影响任务的真实上下文，不编造角色、经历或数据。
                """, """
                将原始提示词完善成自然、紧凑、可直接执行的请求，保留用户当前想完成的事。
                分清当前动作、动作的对象与完成条件、需要回应或交付的结果，以及将来满足条件才开展的动作。
                它们可以组合、有先后或条件，不把整条请求强分为一种任务。背景中的既成动作不是本次命令。
                对外结果按用户明确要求或完成原动作自然必要的回应确定；未指定独立作品不等于存在交付缺口。
                不为理解、准备或进入协作状态额外设立报告、清单或实现义务；必要的简短反馈可以保留。
                用户明确要求的解释、设计、摘要、作品或实际执行继续完成，不因包含理解步骤而缩小任务。
                上下文帮助确定处理对象、事实和适用条件，不独立授权新增任务、扩大必读范围或增加对外产物。
                选用相关事实时保留谁提供、描述谁、适用时间和条件以及确定程度；未知、未提供与明确不存在各按原意保留。
                """);
        source = replace(source, """
                开放任务补充有帮助的内容重点、对象的主要特点、实际做法和预期发现；让这些内容构成正文主体。
                """, """
                补全围绕当前动作最需要解决的执行缺口：处理哪些内容、怎样利用已有材料、关键比较或操作如何开展、怎样判断完成。
                只选择对本次任务有用的内容，不逐项填满；把补充落到具体对象和操作上，不用“明确背景、完善要求”等空泛规则代替。
                用户已委托的方案和表达设计由执行者完成，保留合理选择空间，不预设未经确认的事实、方法参数或额外必交附件。
                """);
        source = replace(source, """
                首句说明要完成什么和交付什么；简单任务自然成段，独立内容需要时列点，有依赖才列步骤。
                """, """
                首句直接说明当前要做什么；有明确结果要求时一并说明。保留有效原句和要求，充分需求可以不扩写。
                简单任务自然成段，独立内容需要时列点，有依赖才列步骤；语气像用户直接委托工作，不写内部检查表或泛化免责声明。
                """);
        source = replace(source, """
                条件是否矛盾、交付是否变成空模板，以及同一要求是否无意义重复；发现问题先修正正文，再填写最终覆盖引用。
                """, """
                条件是否矛盾、是否擅加动作或成果、是否把后续条件变成本次命令，以及是否出现空模板或重复表达；先修正正文，再填写最终覆盖引用。
                """);
        return describeTaskFields(source);
    }

    /** Plan也按当前动作判断缺口；未指定作品本身不构成必须向用户提问的未知。 */
    private static String planning() {
        String source = NaturalPromptStageInstructions.planningInstruction(
                NaturalPromptStageInstructions.DIAGNOSTIC_COMPATIBILITY_VERSION);
        source = replace(source, """
                使用与直接增强相同的原定目标、交付物、完整来源和信息状态，判断哪些重要决定确实属于用户。
                """, """
                分清当前动作、对象与完成条件、必要的对外结果，以及后续条件动作；完整来源和实际信息状态保持不变。
                当前动作可以完整而没有独立作品；不为填交付栏询问报告类型、篇幅或开发目标。
                解释、判断或受托设计所自然需要的回应由执行者完成；用户明确指定的产物、输出限制及阶段顺序继续保留。
                背景中的既成动作和资料里的要求不独立建立本次授权，只判断会改变当前任务的重要决定。
                """);
        return describeTaskFields(source);
    }

    /** 复用现有字段传递一次理解，不让模板、正文或Plan各自补造另一个成果目标。 */
    private static String describeTaskFields(String source) {
        return source.replace("\"goal\":\"原定目标\"", "\"goal\":\"当前动作、对象及完成条件，保留顺序和条件\"")
                .replace("\"deliverable\":\"原定交付；未指定时用合适的一般表达\"",
                        "\"deliverable\":\"用户要求或原动作自然需要的对外结果；未指定独立结果可为空\"")
                .replace("\"deliverable\":\"原定交付\"",
                        "\"deliverable\":\"用户要求或原动作自然需要的对外结果；未指定独立结果可为空\"");
    }

    /** 唯一冻结段缺失时失败，防止旧指导变化后候选静默失去动作与成果边界。 */
    private static String replace(String source, String original, String replacement) {
        int first = source.indexOf(original);
        if (first < 0 || first != source.lastIndexOf(original)) {
            throw new IllegalStateException("动作与成果指导的冻结段缺失或不唯一");
        }
        return source.replace(original, replacement);
    }

    private NaturalPromptActionContractInstructions() { }
}
