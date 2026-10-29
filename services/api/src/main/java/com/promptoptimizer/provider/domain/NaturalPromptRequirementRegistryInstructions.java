package com.promptoptimizer.provider.domain;

/**
 * 49只将原要求出处提取移到已有scope调用，正文沿47业务指导并改用本次reqId引用协议。
 * 不新增调用、思考或场景模板；Plan及旧版本指导冻结，49也不启用48的候选修复数据机制。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptRequirementRegistryInstructions {
    public static final String VERSION = "49";

    public static final String PREPARE = beforeProtocol(NaturalPromptScopedInstructions.PREPARE)
            .replace("当前只识别用户这一轮实际委托的范围，不优化提示词，不执行任务，不寻找补全项，不填写检查表。",
                    "当前只识别用户这一轮实际委托的范围及原有要求，不优化提示词，不执行任务，不寻找补全项。") + """
            同时登记影响当前任务的原有要求，按实际存在的目标、对象、格式、篇幅、独立条件和用户确认核对，
            不把资料每句话都变成执行命令，也不制造六套字段。每份actualAnswers须关联其对应实际来源，未知保留为未知。
            requirements只引用完整原始输入的taskSources，不添加建议、正文引文、充分性结论或Plan问题；编号由服务端赋予。
            只返回JSON：{"actions":[{"text":"原动作及对象","timing":"CURRENT或CONDITIONAL或DESCRIPTIVE",
              "condition":"原条件，无则空字符串","evidence":[{"sourceId":"原来源编号","quote":"连续原句"}]}],
              "response":{"basis":"EXPLICIT或INHERENT或UNSPECIFIED","description":"原回应；UNSPECIFIED时为空",
                "evidence":[{"sourceId":"原来源编号","quote":"连续原句"}]},
              "requirements":[{"text":"原有要求","dimensions":["适用的GOAL或OBJECT或FORMAT或LENGTH或CONDITION或USER_CONFIRMATION"],
                "evidence":[{"sourceId":"原来源编号","quote":"连续原句"}]}]}。
            UNSPECIFIED时response.evidence为空数组；不生成正文、reqId、覆盖记录或额外成果。
            """;

    public static final String ENHANCE = beforeProtocol(NaturalPromptEffectiveRequestInstructions.ENHANCE)
            .replace("requirements合并表达当前要求并引用对应来源", "当前有效要求默认复用准备记录，必要时通过requirementChanges补正并引用原始来源")
            .replace("修正后再填写真实的来源与正文覆盖引文", "修正后按ID填写实际正文引文，仅在requirementChanges中填写需要新增或纠正的原始来源") + """
            preparedRequirements是已核验出处的候选要求清单，不是新事实、授权或完整原输入的替代。
            每个未更正reqId在coverage中恰好出现一次，只回该ID与本次正文的真实单行bodyQuotes，不重抄要求或来源。
            发现准备漏项或误解时，用requirementChanges稀疏补正：replacesId指向已有ID表示替换，null表示新增；
            更正后的requirement显式引用完整原输入中的原始依据，不引用preparedRequirements或reqId作为sourceId。
            不自行发行reqId，不纯删除要求；被替换的错误旧解释不强写回正文，其他独立条件与全部实际题答仍须保留。
            goal、deliverable和最终inputReadiness由当前完整输入及实际正文形成，可纠正早期范围误解，不据分类跳过正文。
            充分需求可以保留原文；无法从现有输入确定的事实和用户选择继续保持未决，不因准备记录存在而自动认定充分。
            additions保留拟议补充的具体缺口、用途及正文引文；openDecisions只保留本轮未决信息，不新增Plan问题。
            返回前逐项核对原文、相关材料及全部实际题答；遗漏须先补正文再更新引文，不能拿来源原句冒充正文。
            只返回JSON：{"optimizedPrompt":"完整自然正文","framework":"参考标记","goal":"当前原定目标",
              "deliverable":"原定回应或成果，可为空","inputReadiness":"SUFFICIENT或NEEDS_COMPLETION",
              "coverage":[{"reqId":"服务端已有编号","bodyQuotes":["本次正文中的连续单行片段"]}],
              "requirementChanges":[{"replacesId":null,"requirement":{"text":"新增或纠正后的原要求",
                "dimensions":["实际适用维度"],"evidence":[{"sourceId":"原来源编号","quote":"连续原句"}]},
                "bodyQuotes":["本次正文中的连续单行片段"]}],
              "additions":[{"text":"拟议补充","gap":"具体执行缺口","benefit":"原任务的直接帮助","bodyQuotes":["本次正文单行片段"]}],
              "openDecisions":[{"issue":"重要未知","impact":"具体影响","kind":"USER_INFORMATION或EXECUTION_CHOICE"}]}。
            无补正、补充或未决时对应数组为空；内部记录不写进优化正文，不要求正文按这些字段组织。
            """;

    public static final String PLAN = NaturalPromptEffectiveRequestInstructions.PLAN;

    /** 只改缺失来源提示的合法字段位置；编号来自原校验器，旧版本提示及其判定完全不变。 */
    public static String missingSourcesRepair(NaturalTaskReview.MissingSources missing, boolean preparation) {
        String ids = String.join("、", new java.util.TreeSet<>(missing.sourceIds()));
        if (preparation) {
            return "\n准备requirements.evidence尚未关联这些现有来源编号：" + ids
                    + "。请核对完整原始题答，在requirements中补齐原要求及连续原句；不要生成reqId、正文或覆盖引用。";
        }
        return "\n正文要求规范化后尚未关联这些现有来源编号：" + ids
                + "。请核对完整原始题答；需要补遗漏或纠正时，在requirementChanges.requirement.evidence显式给出原始出处及对应bodyQuotes。"
                + "已有正文确实覆盖时保留有效表达，未覆盖时先补正文；不重抄未变要求的证据表，不新增事实或确认。";
    }

    /** 只替换版本化JSON职责说明；47的任务写作语义及范围指导原文保留。 */
    private static String beforeProtocol(String source) {
        int boundary = source.indexOf("只返回JSON：");
        if (boundary < 0) throw new IllegalStateException("冻结指导缺少协议边界");
        return source.substring(0, boundary);
    }

    private NaturalPromptRequirementRegistryInstructions() { }
}
