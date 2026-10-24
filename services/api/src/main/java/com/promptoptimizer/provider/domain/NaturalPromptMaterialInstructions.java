package com.promptoptimizer.provider.domain;

/**
 * 自然35复用现有准备调用，先划清执行依赖的解决责任，再把同一记录传给正文和Plan。
 * 记录是有出处的候选理解，不是新的事实；旧4、31、34的指导与解析保持冻结。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptMaterialInstructions {
    public static final String VERSION = "35";

    private static final String COMMON = NaturalPromptInstructions.COMMON.replace(
            "资料不足只限制依赖它的部分，继续保留原定报告、代码、方案等交付，不改成补材料问卷。",
            "当前输入仅表示增强阶段可见的内容，不代表下游执行现场；保持原定动作和成果，不由未附资料推断现实缺失。");

    /** 在已有动作/成果准备中识别解决责任，不新增分析调用，也不枚举每个任务可能需要的所有材料。 */
    public static final String PREPARE = COMMON + """
            当前只识别实际委托，不优化提示词、不执行任务。taskScope区分CURRENT当前动作、CONDITIONAL条件动作、DESCRIPTIVE背景动作，保留对象、条件和顺序。
            response.basis为EXPLICIT明确要求的回应、INHERENT原动作自然需要的回应、UNSPECIFIED未要求独立成果；未指定时description为空，不为理解材料增加说明文档。
            dependencies仅记录容易被误交给用户的执行工作，以及确需用户决定的重要事项；不是必填资料清单，无此类事项时为空。
            EXECUTOR_WORK表示检索、读取执行现场资料、分析、核查或用户委托的设计。action写执行者要做什么，不声称工具或材料已经存在。
            USER_DECISION表示执行者不能合理确定的用户专属事实、保留选择或授权，且答案实质改变结果；reason说明为何属于用户以及改变什么。
            未上传、片段截断、本次未见不构成USER_DECISION依据。也不能一概假定所有信息都能检索：明确没有、不知道、禁止访问和来源冲突仍保留，真实用户决定不能漏掉。
            每项引用原taskSources中的连续原句说明对应哪项任务，不引用准备记录本身。USER_DECISION必须有用户来源，但原文不必出现“未知”字样。
            只返回JSON：{"taskScope":{"actions":[{"text":"原动作及对象","timing":"CURRENT或CONDITIONAL或DESCRIPTIVE","condition":"原条件，无则空",
              "evidence":[{"sourceId":"原来源编号","quote":"连续原句"}]}],
              "response":{"basis":"EXPLICIT或INHERENT或UNSPECIFIED","description":"原回应，UNSPECIFIED时空","evidence":[]}},
              "dependencies":[{"id":"稳定字母数字标识","subject":"涉及的对象或信息","owner":"EXECUTOR_WORK或USER_DECISION",
                "action":"由该责任方完成的动作或决定","reason":"与原任务的关系及结果影响","evidence":[{"sourceId":"原来源编号","quote":"连续原句"}]}]}。
            当前/条件动作及非UNSPECIFIED回应均须有用户来源；UNSPECIFIED回应evidence为空。不要返回正文、问题或推荐答案。
            """;

    private static final String RESPONSIBILITIES = """

            taskScope及executionResponsibilities来自同次准备，是候选理解，不是用户事实或新授权；完整原输入及实际题答仍是依据。
            EXECUTOR_WORK应落实为执行要求，例如直接读取、检索、比较、计算或设计；不能转成用户必须先补资料、选全部方法或填写空模板。
            USER_DECISION保持其用户责任，不能猜实际值或宣称已确认。未出现在输入中的事实不写成“尚未确定”“未提供”背景断言。
            明确没有资料、不知道、禁止访问等原有条件必须保留。执行时才能判断的资料可用性，不在增强时预判；正文仍面向原定成果。
            openDecisions、unaskedDecisions和问题decisionBasis的每条记录须带dependencyId，指向executionResponsibilities中的USER_DECISION。
            每个USER_DECISION须在本阶段的上述列表中保留；Plan可提问或明确保留未问决定，不能用空列表丢掉真实决定。已回答并确定的事实不再归为USER_DECISION。
            准备分类可被原输入纠正：若遗漏或误分真正用户决定，dependencyId使用原id或新的id，并在该条附correction:{"reason":"具体纠正依据","evidence":[{"sourceId":"原用户来源","quote":"连续原句"}]}。
            若准备把已确定事实或已委托工作误归USER_DECISION，在task中可加resolvedDecisions:[{"dependencyId":"原id","reason":"原输入如何已确定或已委托","evidence":[{"sourceId":"原用户来源","quote":"连续原句"}]}]；无需纠正则省略。
            纠正仍只是对原文的解释，不能补造事实或授权；不得仅为通过协议删除真实重要问题。
            没有此类决定时相应列表为空，不把EXECUTOR_WORK或增强可见性单独列为未决事项；确定的限制放在原有要求，不制造用户选择。
            责任标记和dependencyId不进入用户复制正文。inputReadiness只评估请求表达，不判断下游资料是否齐全。
            """;

    /** 保留原有正文来源/覆盖保护，框架仅为可选组织参考，不为框架新增任何要求。 */
    public static final String ENHANCE = NaturalPromptScopedInstructions.PRESERVED_ENHANCE
            .replace(NaturalPromptInstructions.COMMON, COMMON)
            .replace("无法可靠匹配用APE；", "允许不使用框架并省略framework；不按职业固定分配；")
            .replace("\"framework\":\"参考标记\"", "\"framework\":\"可选组织参考，可省略\"")
            .replace("{\"issue\":", "{\"dependencyId\":\"对应USER_DECISION的id\",\"issue\":")
            + RESPONSIBILITIES;

    /** Plan只询问影响结果的用户决定；执行工作不因本阶段尚未执行而重新变成问题。 */
    public static final String PLAN = NaturalPromptScopedInstructions.PLAN
            .replace(NaturalPromptInstructions.COMMON, COMMON)
            .replace("{\"issue\":", "{\"dependencyId\":\"对应USER_DECISION的id\",\"issue\":") + RESPONSIBILITIES;

    private NaturalPromptMaterialInstructions() { }
}
