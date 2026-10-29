package com.promptoptimizer.provider.domain;

/**
 * 67只把有用补全前移至现有准备阶段，原有要求与拟议处理分开，不使用场景模板或少量示例。
 * 65的当前状态解释、正文范围及52的Plan保持；旧版本指导不回改。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptCompletionPreparationInstructions {
    public static final String VERSION = "67";
    /** 68仅补回准备JSON的完整维度枚举；67失败指导保留以复现协议缺陷。 */
    public static final String CORRECTED_VERSION = "68";
    public static final String PREPARE = preparation();
    public static final String CORRECTED_PREPARE = PREPARE.replace("\"dimensions\":[\"适用维度\"]",
            "\"dimensions\":[\"适用的GOAL或OBJECT或FORMAT或LENGTH或CONDITION或USER_CONFIRMATION\"]");
    public static final String BODY_GUIDANCE = """
            completionSuggestions.additions是同次准备得到的拟议处理，不是用户事实、确认或新增权限。选择真正帮助本次任务的内容融入自然请求，不抄内部缺口清单；与原要求不符或重复的提案不采用。原始输入和实际题答仍是依据，提案不能替代它们。
            """;

    private NaturalPromptCompletionPreparationInstructions() { }

    /** 版本仅选择已冻结协议，不根据任务措辞或职业切换策略。 */
    public static boolean supports(String version) {
        return VERSION.equals(version) || CORRECTED_VERSION.equals(version) || NaturalPromptDiagnosticRoles.VERSION.equals(version)
                || NaturalPromptBodyFirstInstructions.WRITER_INPUT_VERSION.equals(version);
    }

    /** 修订只修合法枚举声明，旧协议仍可回放，来源核验及正文指导完全一致。 */
    public static String preparationInstruction(String version) {
        if (CORRECTED_VERSION.equals(version) || NaturalPromptDiagnosticRoles.VERSION.equals(version)
                || NaturalPromptBodyFirstInstructions.WRITER_INPUT_VERSION.equals(version)) return CORRECTED_PREPARE;
        if (VERSION.equals(version)) return PREPARE;
        throw new IllegalArgumentException("补全准备版本不受支持");
    }

    /** 替换唯一的旧准备职责与JSON声明，避免同时要求“不寻找补全”又要求提出补全。 */
    private static String preparation() {
        String source = NaturalPromptBodyFirstInstructions.CURRENT_REQUIREMENTS_PREPARE;
        String old = "不优化提示词，不执行任务，不寻找补全项。";
        if (source.indexOf(old) < 0 || source.indexOf(old) != source.lastIndexOf(old)) {
            throw new IllegalStateException("补全准备的冻结职责缺失或不唯一");
        }
        source = source.replace(old, "不执行原任务；准备原有要求和对其有直接帮助的拟议补充，二者分开。");
        int start = source.indexOf("只返回JSON："), end = source.indexOf("UNSPECIFIED时response.evidence");
        if (start < 0 || end <= start) throw new IllegalStateException("补全准备的冻结协议边界缺失");
        return source.substring(0, start) + """
                原有要求核对后，再考虑执行者还需要知道具体处理什么、怎样完成、结果应包含什么。只提出解决实际表达缺口的少量补充，最多6项；充分需求或无有用补充时为空，不以扩写为目标。
                提案表达下游应完成的处理或设计，不提前给未经执行的结论；用户未提供的事实不改写为不存在。普通检索、阅读和方案选择仍由下游承担，只有用户保留的重要决定保持未决；不把资料清单变成用户的先行补交义务。
                additions与requirements分开：每项写明拟议补充text、具体执行缺口gap和直接用途benefit，不罗列通用质量口号、固定约束或所有可能维度，不补造事实或扩大成果。最终取舍由正文阶段核对。
                只返回JSON：{"actions":[{"text":"原动作及对象","timing":"CURRENT或CONDITIONAL或DESCRIPTIVE",
                  "condition":"原条件，无则空字符串","evidence":[{"sourceId":"原来源编号","quote":"连续原句"}]}],
                  "response":{"basis":"EXPLICIT或INHERENT或UNSPECIFIED","description":"原回应；UNSPECIFIED时为空",
                    "evidence":[{"sourceId":"原来源编号","quote":"连续原句"}]},
                  "requirements":[{"text":"原有要求","dimensions":["适用维度"],"evidence":[{"sourceId":"原来源编号","quote":"连续原句"}]}],
                  "additions":[{"text":"拟议补充","gap":"具体执行缺口","benefit":"原任务的直接帮助"}]}。
                """ + source.substring(end);
    }
}
