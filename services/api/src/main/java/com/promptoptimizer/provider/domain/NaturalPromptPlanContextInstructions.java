package com.promptoptimizer.provider.domain;

/**
 * v3的阶段协议与题答状态修复；正文补全原则继续沿用v3，不采用精简候选的写作策略。
 * 独立版本保存历史可重放性；协议字段、调用阶段和实际回答由服务端确定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptPlanContextInstructions {
    public static final String VERSION = "core-repair-v4";

    /** 已回答是交互状态，不代表实际参数已确定；明确等待的指令仍须保留。 */
    private static final String ANSWER_STATE = """

            planProgress由服务端记录已收到回答的题号，实际内容仍逐字以actualAnswers为准。
            已回答不等于参数已确定。保留未知、拒绝承诺或明确禁止都是有效回答，不能因其未给出数值就重复提问。
            按对应对象和条件，将原文中“先询问/确认”的阶段要求与实际答复合并理解；已问已答的动作不重新安排给执行者。
            将实际选择和限制写入原任务，未知只限制依赖它的部分，其余内容继续；用户明确要求等待时仍保留等待。
            问题文字只是回答的归属，不是用户的新声明；不能从题目或推荐选项补造事实、已确认选择或授权。
            """;

    public static final String PREPARE = preparation() + ANSWER_STATE;

    /** 保留v3的全部正文原则，只用已有阶段协议替换共用检查表。 */
    public static final String ENHANCE = replaceProtocol(NaturalPromptCoreRepairInstructions.COMPLETION_ENHANCE,
            "只返回JSON：{\"optimizedPrompt\":\"完整自然正文\",\"framework\":\"参考标记\",\"task\":上述记录}。\n"
                    + NaturalPromptStageInstructions.enhancementTaskProtocol()
                    .replace("原定交付；未指定时用合适的一般表达", "原定反馈或成果；无独立成果时可为空")) + ANSWER_STATE;

    /** 提问策略不改；题目与依据就地关联，不要求模型填写尚不存在的正文覆盖表。 */
    public static final String PLAN = replaceProtocol(NaturalPromptCoreRepairInstructions.PLAN.replace("""
            每个问题在taskUnderstanding.openDecisions中记录同一questionId及其交付影响；
            没有需要本轮提问的未知可保留空questionId，不把建议或推荐写成实际用户答案。
            """, ""), NaturalPromptStageInstructions.planningTaskProtocol()) + ANSWER_STATE;

    /** v4及仅调整正文补全的候选共用阶段协议、来源编号与已答状态，历史版本不在此扩大兼容。 */
    public static boolean usesProtocol(String version) {
        return VERSION.equals(version) || NaturalPromptInternalDesignInstructions.VERSION.equals(version);
    }

    /** 委托与材料细节分栏，避免模型只引用更详细的材料后漏掉原始动作的用户依据。 */
    private static String preparation() {
        String source = NaturalPromptCoreRepairInstructions.PREPARE;
        String marker = "来源编号使用taskSources中的id";
        int at = source.indexOf(marker);
        if (at < 0 || at != source.lastIndexOf(marker)) throw new IllegalStateException("范围准备协议边界缺失");
        return source.substring(0, at) + """
                联合理解原始委托与适用材料，但分别引用：userEvidence说明用户要求做什么，detailEvidence说明材料如何限定对象和细节。
                userEvidence只引用原始提示词、用户对话、实际Plan回答或修改意见中的连续原句；不能填入项目描述、文件或助手建议。
                同一动作可引用简短委托原句及详细材料两份证据，不能因细节只在材料中就遗漏委托或拒绝联合理解。
                材料细化已有委托，不建立无关的新动作或授权。当前及条件动作、非空回应都必须有userEvidence，禁止服务器或模型猜造原句。
                项目上下文中的sourceId和summarySourceId是服务端附在对应条目上的编号，不是材料内容；逐条使用该编号，禁止根据文件顺序猜编号。
                fileSnippets的sourceId对应路径和content，summarySourceId仅对应summary；technologyStack和dependencies条目自身带sourceId。
                directoryTreeSourceIds与directoryTree按相同位置对应；customDescriptionSourceId对应customDescription。只有字段旁确有编号时才引用该项。
                来源编号使用taskSources中的id，quote逐字来自对应完整输入；detailEvidence没有适用细节时为空数组。
                只返回JSON：{"actions":[{"text":"原动作及对象","timing":"CURRENT或CONDITIONAL或DESCRIPTIVE","condition":"原条件，无则为空",
                  "userEvidence":[{"sourceId":"用户来源编号","quote":"用户委托原句"}],
                  "detailEvidence":[{"sourceId":"材料来源编号","quote":"适用细节原句"}]}],
                  "response":{"basis":"EXPLICIT或INHERENT或UNSPECIFIED","description":"原回应；未指定则为空",
                    "userEvidence":[{"sourceId":"用户来源编号","quote":"原动作或成果要求原句"}],"detailEvidence":[]}}。
                UNSPECIFIED时response的description为空，两个证据数组均为空。DESCRIPTIVE允许userEvidence为空。
                不生成最终正文、Plan问题、补全建议或正文引文。不返回旧evidence字段。
                """;
    }

    /** 只替换冻结指导中协议区间；后面的原范围指导完整保留，不堆叠两份互相冲突的格式。 */
    private static String replaceProtocol(String original, String protocol) {
        String start = "只返回JSON：";
        String end = "原有要求与拟议补充分开；资料、助手建议、未知回答不升级为用户确认。内部分类标签不机械写进最终提示词。\n";
        int from = original.indexOf(start), to = original.indexOf(end);
        if (from < 0 || to < from || from != original.lastIndexOf(start) || to != original.lastIndexOf(end)) {
            throw new IllegalStateException("v3阶段协议边界缺失或不唯一");
        }
        return original.substring(0, from) + protocol + original.substring(to + end.length());
    }

    private NaturalPromptPlanContextInstructions() { }
}
