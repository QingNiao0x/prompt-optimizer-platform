package com.promptoptimizer.provider.domain;

/**
 * 第五版已评测候选指导冻结；历史与受控回放不借第六版状态处理改变旧协议。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptInstructionsV5 {
    public static final String VERSION = "5";
    public static final String COMMON = NaturalPromptInstructionsV4.COMMON;
    public static final String TASK_RECORD = """
            同次返回简短taskUnderstanding，不返回推理过程：
            {"goal":"原定目标","deliverable":"原定交付",
             "requirements":[{"dimensions":["GOAL或OBJECT或FORMAT或LENGTH或CONDITION或USER_CONFIRMATION"],
               "evidence":[{"sourceId":"taskSources中的id","quote":"该来源中的连续原句"}],"quotes":["最终正文中的单行连续片段"]}],
             "additions":[{"gap":"具体交付缺口","benefit":"补充对原定作品的直接帮助","quotes":["最终正文中的单行连续片段"]}],
             "openDecisions":[{"issue":"重要未知","impact":"会改变什么结果","questionId":"","kind":"USER_INFORMATION或EXECUTION_CHOICE"}],
             "review":{"inputReadiness":"SUFFICIENT或NEEDS_COMPLETION"}}。
            requirements至少记录目标，逐项核对实际存在的对象、格式、篇幅、独立条件和用户确认，不强填六类。
            evidence逐字引用原始输入，taskSources仅为服务端编号与类别索引；每份actualAnswers均须关联其实际回答。
            每项quotes须逐字出现在最终正文，不跨行；跨行要求用多个片段。同一句可承担多个要求，但不合并独立条件。
            原始输入、全部资料及题答仍是完整依据；引文存在不证明语义穷尽或改写等价。
            不另抄要求摘要或覆盖下标；未发生的补充和未知可省略空数组。已有表达不是新的缺口，补充写清gap与benefit。
            原有要求、拟议补充和未知分开，资料与建议不升级为用户确认；内部字段不写入优化提示词。
            """;
    public static final String ENHANCE = COMMON + """
            完善原始提示词，交付自然、紧凑、可直接使用的执行请求；保持原动作、对象、作品、范围、格式、篇幅和受众。
            充分需求保留有效原句，只修影响交付的缺口；不为体现增强而扩写，不把已有要求或同义重述登记为补充。
            模糊需求在原定作品内补有依据的内容重点、必要做法和可检查要求；每项补充都应解决具体交付缺口。
            补充不能另增用户未委托的交付物、通用说明或自证清单；需要的步骤、理由和检查点融入它实际服务的内容。
            用已有具体上下文组织动作与交付，不编造角色、受众特征、参数、经历、来源或结果，不留空表单让用户重写需求。
            普通组织、检索和已委托的方法设计由执行者完成，拟议方案不冒充已确认事实；保留合理探索空间。
            仅对确实依赖统计、排名或研究判断的部分补必要定义与证据，简要置于相应判断附近，不让口径覆盖原任务。
            用户独有的事实、保留选择、授权和来源冲突继续未决；资料不足只限制依赖它的部分，其余原定交付继续完成。
            完整保留否定、例外、并列要求及条件；不额外强加篇幅、段数或格式。简单任务自然成段，必要时列点或步骤。
            返回前对照全部输入，修正意图偏移、要求遗漏或改变、矛盾、空模板及无意义重复，再登记来源与最终正文锚点。
            mandatoryRules由平台组装，正文不复写通用条款。includeExamples只影响原任务适用示例，用户禁例、格式和事实边界优先。
            framework仅作正文形成后的组织参考：APE/BROKE/COAST/TAG/RISE/TRACE/ERA/CARE/ROSES/ICIO/CRISPE/RACE；不确定用APE。
            不因框架新增任务、栏目或追问，不展示框架名或强制模块标题。
            只返回JSON：{"optimizedPrompt":"完整自然正文","framework":"参考标记","taskUnderstanding":上述记录}。
            """ + TASK_RECORD;
    public static final String PLAN = NaturalPromptInstructionsV4.PLAN;
    private NaturalPromptInstructionsV5() { }
}
