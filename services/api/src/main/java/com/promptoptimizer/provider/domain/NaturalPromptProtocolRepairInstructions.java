package com.promptoptimizer.provider.domain;

/**
 * v4的协议定位与限制范围修复，沿用原阶段DTO和正文补全策略。
 * 独立版本可回放历史指导；只有本版本使用完整根对象示意及范围核对。
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptProtocolRepairInstructions {
    public static final String VERSION = "core-repair-v5";
    private static final String SCOPE = """

            明确限制的必要短句优先沿用原文，保留对象、范围限定、例外与生效条件；同一正文其他位置不得追加更强或更弱的限制。
            对每项限制核对其允许范围和禁止范围，不能因改写更简洁就扩大禁止对象、删除限定词或新增允许例外。
            """;
    public static final String ENHANCE = enhancement();
    public static final String PLAN = NaturalPromptPlanContextInstructions.PLAN + SCOPE;

    /** 将外层和内层示意合成一个JSON，语义字段及来源、覆盖规则逐字复用原协议。 */
    private static String enhancement() {
        String originalProtocol = "只返回JSON：{\"optimizedPrompt\":\"完整自然正文\",\"framework\":\"参考标记\",\"task\":上述记录}。\n"
                + NaturalPromptStageInstructions.enhancementTaskProtocol()
                .replace("原定交付；未指定时用合适的一般表达", "原定反馈或成果；无独立成果时可为空");
        String completeProtocol = NaturalPromptStageInstructions.enhancementTaskProtocol()
                .replace("与正文一起返回简短task，不另发分析或推理过程：", "只返回以下完整JSON对象，每个对象内字段只出现一次，不在末尾重复添加空数组：")
                .replace("{\"goal\":\"原定目标\"", "{\"optimizedPrompt\":\"完整自然正文\",\"framework\":\"参考标记\",\"task\":{\"goal\":\"原定目标\"")
                .replace("USER_INFORMATION或EXECUTION_CHOICE\"}]}。", "USER_INFORMATION或EXECUTION_CHOICE\"}]}}。")
                .replace("原定交付；未指定时用合适的一般表达", "原定反馈或成果；无独立成果时可为空");
        if (!NaturalPromptPlanContextInstructions.ENHANCE.contains(originalProtocol)) {
            throw new IllegalStateException("原阶段协议边界缺失");
        }
        return NaturalPromptPlanContextInstructions.ENHANCE.replace(originalProtocol, completeProtocol) + SCOPE;
    }

    private NaturalPromptProtocolRepairInstructions() { }
}
