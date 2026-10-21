package com.promptoptimizer.provider.domain;

/**
 * 独立自然链路的版本化生成指导。框架只组织适用信息，不作为用户必须填满的表单。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptInstructions {
    /** 第五版保留为已评测候选；确认后质量与重试收益未通过，普通请求继续使用第四版。 */
    public static final String DEFAULT_VERSION = NaturalPromptInstructionsV4.VERSION;
    /** 最新可兼容的候选协议版本，不代表已采用为默认。 */
    public static final String VERSION = "5";

    /** 已保存的自然版本继续使用各自冻结指导，不静默改换再次增强策略。 */
    public static boolean supports(String version) {
        return VERSION.equals(version) || NaturalPromptInstructionsV4.VERSION.equals(version) || NaturalPromptInstructionsV3.VERSION.equals(version) || NaturalPromptInstructionsV2.VERSION.equals(version)
                || NaturalPromptInstructionsV1.VERSION.equals(version);
    }

    /** 第二版起均保留来源与任务理解协议；升级写作策略不能放松旧版本的协议保护。 */
    public static boolean requiresTaskUnderstanding(String version) {
        return supports(version) && !NaturalPromptInstructionsV1.VERSION.equals(version);
    }

    /** 第四版起核对覆盖和提问影响记录；旧历史保持原协议，不补造旧版没有的检查证据。 */
    public static boolean requiresTaskReview(String version) {
        return VERSION.equals(version) || NaturalPromptInstructionsV4.VERSION.equals(version);
    }

    /** 以保存的版本选择原生成指导；正文写法更新不静默改写旧历史的再次增强策略。 */
    public static String enhancementInstruction(String version) {
        return switch (version) {
            case "1" -> NaturalPromptInstructionsV1.ENHANCE;
            case "2" -> NaturalPromptInstructionsV2.ENHANCE;
            case "3" -> NaturalPromptInstructionsV3.ENHANCE;
            case "4" -> NaturalPromptInstructionsV4.ENHANCE;
            case VERSION -> ENHANCE;
            default -> throw new IllegalArgumentException("自然增强版本不受支持");
        };
    }

    /** Plan采用同版本的任务责任边界；旧版对照不复用新版提问指导。 */
    public static String planningInstruction(String version) {
        return switch (version) {
            case "1" -> NaturalPromptInstructionsV1.PLAN;
            case "2" -> NaturalPromptInstructionsV2.PLAN;
            case "3" -> NaturalPromptInstructionsV3.PLAN;
            case "4" -> NaturalPromptInstructionsV4.PLAN;
            case VERSION -> PLAN;
            default -> throw new IllegalArgumentException("自然增强版本不受支持");
        };
    }
    public static final String COMMON = NaturalPromptInstructionsV4.COMMON;

    /** 只改变同次生成记录的组织；来源原句、缺口作用及全部正文锚点仍由原校验核对。 */
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

    /** 提问沿用当前完整协议与八题约定；本轮仅收敛最终自然生成，不改变用户决定的归属。 */
    public static final String PLAN = NaturalPromptInstructionsV4.PLAN;
    private NaturalPromptInstructions() { }
}
