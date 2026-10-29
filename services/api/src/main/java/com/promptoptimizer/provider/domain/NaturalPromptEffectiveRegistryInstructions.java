package com.promptoptimizer.provider.domain;

/**
 * 52仅在已有真实题答的准备调用中登记当前有效要求，复用47的局部确认生效关系。
 * 无题答准备、正文、Plan及reader沿51；完整历史来源与全部题答仍保留，不新增字段或语义调用。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptEffectiveRegistryInstructions {
    public static final String VERSION = "52";
    public static final String PREPARE = NaturalPromptRegistryDiagnosticInstructions.PREPARE;
    public static final String ENHANCE = NaturalPromptRegistryDiagnosticInstructions.ENHANCE;
    public static final String PLAN = NaturalPromptRegistryDiagnosticInstructions.PLAN;

    public static final String PREPARE_WITH_ANSWERS = replaceUnique(replaceUnique(replaceUnique(PREPARE,
            "当前只识别用户这一轮实际委托的范围及原有要求，不优化提示词，不执行任务，不寻找补全项。\n",
            "当前只根据完整原始输入和本次真实题答准备当前有效的动作、回应范围及要求，不优化提示词，不执行任务，不寻找补全项。\n"),
            "actions和response只识别用户实际委托；requirements登记该委托的原有要求及适用上下文边界，两者职责分开。\n", """
            actions、response和requirements共同表达当前有效要求，不分别沿用确认前后互相冲突的解释。
            actualAnswers中的真实确认，只在同一对象、属性及适用条件内，更新先前未决或由用户明确修正的要求；其他独立条件和权限边界仍保留。
            依据实际回答判断更新，题干只帮助界定回答的作用范围；未选选项、助手建议和保留未知的回答都不构成新决定。
            requirements用一套当前有效text表达已更新的要求，evidence显式保留相应原始依据和实际回答；
            被确认更新的旧取值留在完整原文、原问题和题答来源中，不再单列成同时执行的要求，也不为保留出处而复写旧指令。
            actions的动作文字、适用条件及response的回应范围使用同一有效解释；仅当原确认条件已经由相应回答满足时，按当前状态表达，
            不再将它写成仍待确认的将来条件；其他尚未满足的未来条件继续保留，不因存在回答就启动新动作或增加回应。
            """),
            "不逐句搬运材料，不为登记增加动作或对外回应。每份actualAnswers须关联对应实际来源，否定、条件和未知保持原状态。\n", """
            不逐句搬运材料，不为登记增加动作或对外回应。每份actualAnswers仍须逐份关联其实际来源，不能只引用问题代替实际回答。
            未被相应确认更新的独立否定、条件和选择继续保留，未知仍是未知；不能因有题答就自动认定参数已经确定。
            """);

    /** 只替换冻结51准备中的唯一职责段；找不到原段时失败，避免静默混用新旧生效说明。 */
    private static String replaceUnique(String source, String original, String replacement) {
        int first = source.indexOf(original);
        if (first < 0 || first != source.lastIndexOf(original)) {
            throw new IllegalStateException("51准备职责的冻结段已变化，不能静默替换");
        }
        return source.replace(original, replacement);
    }

    private NaturalPromptEffectiveRegistryInstructions() { }
}
