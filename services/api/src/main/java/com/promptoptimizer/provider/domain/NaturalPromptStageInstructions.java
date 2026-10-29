package com.promptoptimizer.provider.domain;

/**
 * 按增强与Plan阶段减少重复协议字段的受控候选。沿用第四版业务指导，不增加生成阶段。
 * 21只改变协议，22仅取消框架标记必填，23再移除十二框架组织参考；24沿用21指导，仅兼容无权威诊断字段。
 * 27复用24协议，将动作与成果的语义指导交给独立候选，不回改21—24冻结内容。
 * 28先识别范围，再复用阶段响应及覆盖校验；模型不重复填写目标和成果。
 * 默认版本仍由原入口管理，候选存在不表示质量通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptStageInstructions {
    public static final String VERSION = "21";
    public static final String OPTIONAL_FRAMEWORK_VERSION = "22";
    public static final String NO_FRAMEWORK_VERSION = "23";
    public static final String DIAGNOSTIC_COMPATIBILITY_VERSION = "24";

    private static final String FRAMEWORK_START = "framework只是正文形成后的组织参考：";
    // 从冻结的第四版截取原业务指导，找不到唯一协议边界即失败，不默默更改旧版或猜测新边界。
    private static final String ENHANCEMENT_GUIDANCE = before(NaturalPromptInstructions.ENHANCE, FRAMEWORK_START);
    private static final String FRAMEWORK_GUIDANCE = between(NaturalPromptInstructions.ENHANCE,
            FRAMEWORK_START, "只返回JSON：");
    private static final String PLANNING_GUIDANCE = before(NaturalPromptInstructions.PLAN,
            "每个问题在taskUnderstanding.openDecisions中记录")
            + between(NaturalPromptInstructions.PLAN, "用户独有的实际情况、数值、资源边界或保留参数属于USER_INFORMATION：",
                    "只返回JSON：");

    private static final String SOURCE_GUIDANCE = """
            taskSources只提供服务端来源编号和类别，id对应完整输入路径；从原输入引用，不编造来源或原句。
            requirements至少记录原定目标，按实际存在的目标、对象、格式、篇幅、独立条件和用户确认逐项核对；
            dimensions只选GOAL、OBJECT、FORMAT、LENGTH、CONDITION、USER_CONFIRMATION中的适用类别，不制造六套字段。
            每份actualAnswers都需在evidence中引用其实际回答，未知仍保留为未知；相同文字的不同题答不合并来源。
            原始提示词、全部来源和actualAnswers仍是完整依据，不能用本记录替代，也不能从引用存在推断已覆盖全部要求。
            原有要求与拟议补充分开；资料、助手建议、未知回答不升级为用户确认。内部分类标签不机械写进最终提示词。
            仅返回本阶段规定的JSON字段；来源类别、数组下标、阶段和题目关联由服务端确定，不额外返回这些字段。
            """;
    private static final String ENHANCEMENT_PROTOCOL = """
            与正文一起返回简短task，不另发分析或推理过程：
            {"goal":"原定目标","deliverable":"原定交付；未指定时用合适的一般表达",
             "inputReadiness":"SUFFICIENT或NEEDS_COMPLETION",
             "requirements":[{"text":"影响交付的原有要求","dimensions":["适用类别"],
               "evidence":[{"sourceId":"taskSources中的id","quote":"来源中的连续原句"}],
               "bodyQuotes":["最终正文中的连续原句"]}],
             "additions":[{"text":"拟议补充","gap":"现有表达缺少什么且为何影响交付","benefit":"对原定交付的直接帮助",
               "bodyQuotes":["最终正文中的连续原句"]}],
             "openDecisions":[{"issue":"仍然重要的未知","impact":"只影响哪部分交付","kind":"USER_INFORMATION或EXECUTION_CHOICE"}]}。
            充分需求保留有效表达，additions可为空；有补充时记录具体缺口与交付作用，不添加通用空槽。
            每条原有要求和拟议补充的bodyQuotes必须逐字存在于最终正文，使用单行片段；跨行要求可引用多个片段。
            同一句可承担多项要求，但不同来源、独立条件、否定和例外不能因共用一句而删掉；正文排版不受引用协议限制。
            未出现的条件不杜撰；无补充或未知时对应数组为空。现有实际题答已在输入中，不再次生成问题或题号。
            本阶段不返回questions、planImpacts、review、requirementCoverage、additionCoverage或旧taskUnderstanding。
            """ + SOURCE_GUIDANCE;
    private static final String PLANNING_PROTOCOL = """
            只返回JSON：{"summary":"原定任务及必要澄清的简短说明",
             "task":{"goal":"原定目标","deliverable":"原定交付","inputReadiness":"SUFFICIENT或NEEDS_COMPLETION",
               "requirements":[{"text":"影响交付的原有要求","dimensions":["适用类别"],
                 "evidence":[{"sourceId":"taskSources中的id","quote":"来源中的连续原句"}]}],
               "unaskedDecisions":[{"issue":"本轮未提问但仍未决的信息","impact":"会影响哪部分交付","kind":"USER_INFORMATION或EXECUTION_CHOICE"}]},
             "questions":[{"id":"稳定字母数字标识","question":"重要问题","hint":"为何影响交付",
               "type":"FREE_TEXT或SINGLE_CHOICE或MULTIPLE_CHOICE","options":[
                 {"id":"标识","label":"选项","description":"含义","answer":"该选择的完整含义",
                  "recommended":false,"recommendationReason":""}],"examples":[],"allowCustomAnswer":true,
               "decisionBasis":[{"issue":"此题涉及的重要未知","kind":"USER_INFORMATION或EXECUTION_CHOICE",
                 "whyAsk":"为什么现有输入无法确定且需要用户决定","answerDifference":"不同答案怎样改变具体交付、事实判断或权限"}]}]}。
            每道问题的decisionBasis不可为空；同题涉及多个独立未知时逐项保留，不能只记录第一项。
            题号只写在questions.id一次，不在依据中重复；不同答案的影响不能仅写“提高质量”。
            充分需求允许questions为空；无未提问的重要未知时unaskedDecisions为空，不凑题或把未问事项写成已解决。
            本阶段不生成最终正文，不返回optimizedPrompt、framework、additions、bodyQuotes、review、任何Coverage、planImpacts或旧taskUnderstanding。
            """ + SOURCE_GUIDANCE;

    private NaturalPromptStageInstructions() { }

    /** 仅服务端显式选择的候选采用新协议，旧历史继续使用原指导。 */
    public static boolean supports(String version) {
        return NaturalPromptPreparationRoutingInstructions.VERSION.equals(version)
                || NaturalPromptIntentCompletionInstructions.VERSION.equals(version)
                || NaturalPromptContentScopeInstructions.VERSION.equals(version)
                || VERSION.equals(version) || OPTIONAL_FRAMEWORK_VERSION.equals(version) || NO_FRAMEWORK_VERSION.equals(version)
                || DIAGNOSTIC_COMPATIBILITY_VERSION.equals(version) || NaturalPromptActionContractInstructions.VERSION.equals(version)
                || NaturalPromptSinglePassInstructions.supports(version)
                || NaturalPromptBodyFirstInstructions.supports(version)
                || NaturalPromptScopedInstructions.supports(version);
    }

    /** 21/24仍校验原十二框架标记，22/23将其降为不影响正文交付的可选诊断。 */
    public static boolean requiresFramework(String version) {
        // 35只保留组织参考；未选择框架不阻断合法正文，旧版本必填契约不变。
        if (NaturalPromptMaterialInstructions.VERSION.equals(version)
                || NaturalPromptPreparationRoutingInstructions.VERSION.equals(version)
                || NaturalPromptIntentCompletionInstructions.VERSION.equals(version)
                || NaturalPromptContentScopeInstructions.VERSION.equals(version)) return false;
        return VERSION.equals(version) || DIAGNOSTIC_COMPATIBILITY_VERSION.equals(version)
                || NaturalPromptActionContractInstructions.VERSION.equals(version)
                || NaturalPromptSinglePassInstructions.supports(version) || NaturalPromptBodyFirstInstructions.supports(version)
                || NaturalPromptScopedInstructions.supports(version);
    }

    /** 24及其动作契约候选沿用相同诊断兼容；已知语义字段、来源和跨阶段字段仍严格校验。 */
    public static boolean acceptsDiagnosticFields(String version) {
        return NaturalPromptPreparationRoutingInstructions.VERSION.equals(version)
                || NaturalPromptIntentCompletionInstructions.VERSION.equals(version)
                || NaturalPromptContentScopeInstructions.VERSION.equals(version)
                || DIAGNOSTIC_COMPATIBILITY_VERSION.equals(version) || NaturalPromptActionContractInstructions.VERSION.equals(version)
                || NaturalPromptSinglePassInstructions.supports(version)
                || NaturalPromptBodyFirstInstructions.supports(version)
                || NaturalPromptScopedInstructions.supports(version);
    }

    /** 21—23逐一消融协议、框架必填及框架指导；24必须逐字复用21，兼容处理仅发生在读取端。 */
    public static String enhancementInstruction(String version) {
        if (!supports(version)) throw new IllegalArgumentException("分阶段自然增强版本不受支持");
        if (NaturalPromptSinglePassInstructions.supports(version)) return NaturalPromptSinglePassInstructions.enhancementInstruction(version);
        if (NaturalPromptBodyFirstInstructions.supports(version)) return NaturalPromptBodyFirstInstructions.bodyInstruction(version);
        if (NaturalPromptContentScopeInstructions.VERSION.equals(version)) return NaturalPromptContentScopeInstructions.ENHANCE;
        if (NaturalPromptIntentCompletionInstructions.VERSION.equals(version)) return NaturalPromptIntentCompletionInstructions.ENHANCE;
        if (NaturalPromptScopedInstructions.GUARDED_FOURTH_VERSION.equals(version)) return NaturalPromptInstructions.ENHANCE;
        if (NaturalPromptScopedInstructions.PRESERVED_VERSION.equals(version)
                || NaturalPromptScopedInstructions.CONSERVATIVE_VERSION.equals(version)) return NaturalPromptScopedInstructions.PRESERVED_ENHANCE;
        if (NaturalPromptScopedInstructions.VERSION.equals(version)) return NaturalPromptScopedInstructions.ENHANCE;
        if (NaturalPromptActionContractInstructions.VERSION.equals(version)) return NaturalPromptActionContractInstructions.ENHANCE;
        String framework = NO_FRAMEWORK_VERSION.equals(version) ? "" : FRAMEWORK_GUIDANCE;
        String envelope = requiresFramework(version)
                ? "只返回JSON：{\"optimizedPrompt\":\"完整自然正文\",\"framework\":\"参考标记\",\"task\":上述记录}。\n"
                : "只返回JSON：{\"optimizedPrompt\":\"完整自然正文\",\"task\":上述记录}；framework为可选诊断标记，不填写不影响正文。\n";
        return ENHANCEMENT_GUIDANCE + framework + envelope + ENHANCEMENT_PROTOCOL;
    }

    /** 阶段候选使用同一Plan协议，解析兼容和框架消融不改变提问要求或真实题答绑定。 */
    public static String planningInstruction(String version) {
        if (!supports(version)) throw new IllegalArgumentException("分阶段自然Plan版本不受支持");
        if (NaturalPromptSinglePassInstructions.supports(version)) return NaturalPromptSinglePassInstructions.PLAN;
        if (NaturalPromptBodyFirstInstructions.supports(version)) return NaturalPromptBodyFirstInstructions.PLAN;
        if (NaturalPromptContentScopeInstructions.VERSION.equals(version)) return NaturalPromptContentScopeInstructions.PLAN;
        if (NaturalPromptIntentCompletionInstructions.VERSION.equals(version)) return NaturalPromptIntentCompletionInstructions.PLAN;
        if (NaturalPromptScopedInstructions.supports(version)) return NaturalPromptScopedInstructions.PLAN;
        if (NaturalPromptActionContractInstructions.VERSION.equals(version)) return NaturalPromptActionContractInstructions.PLAN;
        return PLANNING_GUIDANCE + PLANNING_PROTOCOL;
    }

    /** 54复用既有增强wire文本，调用方仅替换成果说明；原24指导和字段契约均不重新生成。 */
    static String enhancementTaskProtocol() { return ENHANCEMENT_PROTOCOL; }

    /** 定位冻结指导中的唯一边界，避免字符串模板漂移被当成协议单变量。 */
    private static int boundary(String source, String marker) {
        int index = source.indexOf(marker);
        if (index < 0 || source.indexOf(marker, index + marker.length()) >= 0) {
            throw new IllegalStateException("第四版指导边界缺失或不唯一");
        }
        return index;
    }

    /** 截取阶段协议开始之前的第四版业务指导。 */
    private static String before(String source, String marker) {
        return source.substring(0, boundary(source, marker));
    }

    /** 保留选定区间的原字节与换行，不改写第四版实际执行指导。 */
    private static String between(String source, String start, String end) {
        int from = boundary(source, start), to = boundary(source, end);
        if (from >= to) throw new IllegalStateException("第四版指导边界顺序无效");
        return source.substring(from, to);
    }
}
