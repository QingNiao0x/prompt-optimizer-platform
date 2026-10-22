package com.promptoptimizer.provider.domain;

/**
 * 第四版单次生成中的信息状态保真与上下文取舍候选。
 * 只调整增强业务指导和任务记录说明；沿用原协议、来源及覆盖校验，Plan指导和调用阶段不变。
 * 模型指导不能替代语义验收，是否采用仍由未见题和同信息实际作品验证决定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptInformationStateInstructions {
    public static final String VERSION = "25";
    public static final String ENHANCE = refineFourthVersion();

    /**
     * 来源状态与目标作品的内容取舍分别约束：可用资料不足不能成为现实断言，也不自动成为对外披露要求。
     * 仅替换两个唯一冻结段，不改第四版常量或其他候选；明确事实、用户要求及真实题答继续保留。
     */
    private static String refineFourthVersion() {
        String contextOriginal = """
                把已提供的具体信息直接用于相应动作与交付要求；背景只用影响任务的真实上下文，不编造角色、经历或数据。
                """;
        String contextReplacement = """
                把已提供的具体信息直接用于相应动作与交付要求；背景只用确实影响任务的上下文，不编造角色、经历或数据。
                引用、概括和转述时保留主体、对象、来源范围、条件和确定程度；本次资料未提供或未证实，不等于实际不存在或未执行。
                来源明确说明不存在、未执行或已经确定的情况，按其原有范围和实际状态保留，不统一改成资料不足或未知。
                资料可用性只限制依赖它的判断，不自动成为目标作品必须向受众陈述的内容；不影响交付的资料缺口不强行带入作品。
                用户明确要求说明缺口，或缺口确实影响具体交付时，在对应判断或执行要求中保留必要边界，仍完成其余交付；
                用户原始要求、已确认信息、未知回答及全部实际题答的独立条件，不因上述取舍被删除或改成新的确认。
                """;
        String requirementsOriginal = """
                requirements至少记录原定目标，按实际存在的目标、对象、格式、篇幅、独立条件和用户确认逐项核对；
                """;
        String requirementsReplacement = """
                requirements记录原始要求，以及确实影响交付的上下文事实或判断边界；至少保留原定目标，按实际存在的目标、对象、格式、篇幅、独立条件和用户确认逐项核对。
                每项text与evidence保持同一主体、对象、来源范围、条件和确定程度；完整引用不能替代忠实概括，也不能证明改写后的断言得到支持。
                资料缺口若只限制某项判断，就记录相应执行边界，不把缺口提升为现实状态或目标作品必须披露的内容；用户明确的披露要求仍完整保留。
                """;
        return replaceUnique(replaceUnique(NaturalPromptInstructions.ENHANCE, contextOriginal, contextReplacement),
                requirementsOriginal, requirementsReplacement);
    }

    /** 冻结段缺失或重复时明确失败，防止基线漂移导致替换范围扩张或候选静默失效。 */
    private static String replaceUnique(String source, String original, String replacement) {
        int first = source.indexOf(original);
        if (first < 0 || first != source.lastIndexOf(original)) {
            throw new IllegalStateException("第四版信息状态指导的冻结段已变化，不能静默替换");
        }
        return source.replace(original, replacement);
    }

    private NaturalPromptInformationStateInstructions() { }
}
