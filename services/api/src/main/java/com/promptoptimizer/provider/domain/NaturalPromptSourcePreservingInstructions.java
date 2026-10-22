package com.promptoptimizer.provider.domain;

/**
 * 第四版单次生成中分开处理任务指令与来源事实的受控候选。
 * 任务表达可完善，需带入的材料事实使用完整来源原句；仍沿用第四版响应、来源、覆盖及Plan协议。
 * 原句选择仍可能遗漏前置条件或上下文，逐字引用不等于语义保真；是否采用需由未见题和实际作品验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptSourcePreservingInstructions {
    public static final String VERSION = "26";
    public static final String ENHANCE = refineFourthVersion();

    /**
     * 仅替换第四版两个冻结段，分别说明正文材料的选用方式与原有任务记录的对应方式。
     * 不叠加候选25的解释性指导，不改变用户委托设计、全部实际题答及原有覆盖保护。
     */
    private static String refineFourthVersion() {
        String contextOriginal = """
                把已提供的具体信息直接用于相应动作与交付要求；背景只用影响任务的真实上下文，不编造角色、经历或数据。
                """;
        String contextReplacement = """
                分开处理任务指令与材料事实：可完善任务指令的表达和必要执行要求，用户明确的目标、范围、格式、篇幅、条件及已确认选择保持不变。
                只选影响交付的材料事实，使用保留主体、对象、来源范围、条件和确定程度的完整原句，作为执行者的材料依据，不改写成独立现实断言。
                材料依据不是必须向受众逐条披露的清单；具体表达按原定交付和用户要求处理，明确要求说明的事实、资料缺口及未决信息仍保留。
                """;
        String requirementsOriginal = """
                requirements至少记录原定目标，按实际存在的目标、对象、格式、篇幅、独立条件和用户确认逐项核对；
                """;
        String requirementsReplacement = """
                requirements记录原定目标和原有要求，按实际存在的目标、对象、格式、篇幅、独立条件和用户确认逐项核对。
                对需带入的材料事实，text与evidence采用同一来源的完整事实原句，保留理解该事实所需的归属和前后条件；正文以该原句作为材料依据，对应Coverage引用正文中的该片段。
                任务指令可在忠实保留要求的前提下整理表达，材料的存在不自动新增对受众的披露要求。
                """;
        return replaceUnique(replaceUnique(NaturalPromptInstructions.ENHANCE, contextOriginal, contextReplacement),
                requirementsOriginal, requirementsReplacement);
    }

    /** 冻结段缺失或重复时明确失败，避免基线漂移后静默扩大替换范围或失去候选差异。 */
    private static String replaceUnique(String source, String original, String replacement) {
        int first = source.indexOf(original);
        if (first < 0 || first != source.lastIndexOf(original)) {
            throw new IllegalStateException("第四版来源原句指导的冻结段已变化，不能静默替换");
        }
        return source.replace(original, replacement);
    }

    private NaturalPromptSourcePreservingInstructions() { }
}
