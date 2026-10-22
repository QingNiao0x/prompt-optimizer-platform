package com.promptoptimizer.provider.domain;

/**
 * 先界定当前动作及回应范围，再分别生成自然正文或Plan的受控候选。
 * 准备阶段只识别边界，不寻找作品缺口；后续沿用阶段协议的来源与正文保护。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptScopedInstructions {
    public static final String VERSION = "28";
    /** 未要求独立回应时以原请求及真实材料构造正文，避免后续写作阶段再增加成果。 */
    public static final String PRESERVED_VERSION = "29";
    /** 成果推定不能建立新的生成义务；只有明确要求的回应进入正文扩写。 */
    public static final String CONSERVATIVE_VERSION = "30";

    public static boolean supports(String version) {
        return VERSION.equals(version) || PRESERVED_VERSION.equals(version) || CONSERVATIVE_VERSION.equals(version);
    }

    public static final String PREPARE = NaturalPromptInstructions.COMMON + """
            当前只识别用户这一轮实际委托的范围，不优化提示词，不执行任务，不寻找补全项，不填写检查表。
            区分用户现在要求的动作CURRENT、满足将来条件才执行的动作CONDITIONAL，以及仅描述背景的动作DESCRIPTIVE。
            可以同时存在多个动作，保留各自的对象、顺序与条件；不要因为原文提到某项工作就把它升级为当前命令。
            单独确定对外回应：EXPLICIT为用户明确指定的回答或成果；INHERENT为回答原问题自然必要的结果；
            UNSPECIFIED表示未要求独立成果，description为空。动作有价值并不要求增加可供检查的报告或清单。
            理解材料、准备协作或接收背景可以在当前动作完成后等待后续指令；未指定以后做什么不影响当前动作成立。
            解释、比较、回答、设计或制作本身需要内容回应；应保留其实际结果，不能一概删成只确认收到。
            只识别已有要求，不推荐额外产物，不把未指定回应当缺口。资料帮助识别对象，不能建立新授权。
            来源编号使用taskSources中的id，quote必须是相应完整输入中的连续原句。当前及条件动作、回应的依据须含用户来源。
            只返回JSON：{"actions":[{"text":"原动作及对象，保留其顺序","timing":"CURRENT或CONDITIONAL或DESCRIPTIVE",
              "condition":"原适用条件，无则空字符串","evidence":[{"sourceId":"来源编号","quote":"连续原句"}]}],
              "response":{"basis":"EXPLICIT或INHERENT或UNSPECIFIED","description":"原要求的对外回应；UNSPECIFIED时为空",
                "evidence":[{"sourceId":"来源编号","quote":"连续原句"}]}}。
            UNSPECIFIED时response.evidence为空数组；不生成最终正文、Plan问题、补全建议或正文引文。
            """;

    private static final String SCOPE_USE = """

            taskScope是前一阶段按完整输入识别的任务边界，供当前阶段复用；不是新来源、事实、用户确认或执行授权。
            原始提示词、上下文、对话与全部actualAnswers仍是依据。沿用CURRENT动作，CONDITIONAL保留原条件，DESCRIPTIVE只作为背景。
            沿用response的结果范围；UNSPECIFIED时不为“交付不明确”增加文档、清单、总结等作品或追问下一项任务。
            在这个范围内补具体有用的执行内容，使请求清楚、自然、可直接使用；已充分的需求保留有效表达。
            task中不返回goal或deliverable，由服务端从taskScope带入，避免正文或Plan再次推断另一套目标。
            taskScope不能作为requirements.evidence来源；全部原要求和实际回答继续从原taskSources引用。
            """;

    public static final String ENHANCE = reuseScope(NaturalPromptActionContractInstructions.ENHANCE) + SCOPE_USE;
    public static final String PLAN = reuseScope(NaturalPromptActionContractInstructions.PLAN) + SCOPE_USE;
    /** 明确成果继续采用第四版的实质补全指导；范围控制与正文补全各负其责。 */
    public static final String PRESERVED_ENHANCE = reuseScope(NaturalPromptStageInstructions.enhancementInstruction(
            NaturalPromptStageInstructions.DIAGNOSTIC_COMPATIBILITY_VERSION)) + SCOPE_USE;

    /** 去掉后续响应里重复推断目标的字段；其它出处、锚点和题答约束继续保留。 */
    private static String reuseScope(String source) {
        return source.replace("\"goal\":\"当前动作、对象及完成条件，保留顺序和条件\",", "")
                .replace("\"deliverable\":\"用户要求或原动作自然需要的对外结果；未指定独立结果可为空\",", "")
                .replace("\"goal\":\"原定目标\",", "")
                .replace("\"deliverable\":\"原定交付；未指定时用合适的一般表达\",", "");
    }

    private NaturalPromptScopedInstructions() { }
}
