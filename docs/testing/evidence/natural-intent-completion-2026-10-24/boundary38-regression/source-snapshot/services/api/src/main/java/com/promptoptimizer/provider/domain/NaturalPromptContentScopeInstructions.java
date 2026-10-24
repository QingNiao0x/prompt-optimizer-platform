package com.promptoptimizer.provider.domain;

/**
 * 自然38区分新增独立交付义务与完善原成果内部内容。
 * 只替换37的一处补充边界，准备、Plan、响应协议及生成顺序保持不变；默认策略不随候选注册切换。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptContentScopeInstructions {
    public static final String VERSION = "38";
    public static final String PREPARE = NaturalPromptIntentCompletionInstructions.PREPARE;
    public static final String PLAN = NaturalPromptIntentCompletionInstructions.PLAN;
    public static final String ENHANCE = enhancement();

    /** 原成果内的具体化可以帮助执行，但不能扩展成果类型、用户前置义务或执行权限。 */
    private static String enhancement() {
        String source = NaturalPromptIntentCompletionInstructions.ENHANCE;
        String original = "且不增加成果、前置资料义务或授权。";
        int at = source.indexOf(original);
        if (at < 0 || source.indexOf(original, at + original.length()) >= 0) {
            throw new IllegalStateException("自然38补充边界缺失或不唯一");
        }
        String replacement = "保持原定成果类型、范围和明确格式，不擅自增加独立交付义务、用户前置资料义务或执行授权。"
                + "允许具体化原成果内部有用的内容组成、必要操作关系及用户已委托的设计；拟议做法不冒充事实或用户确认。";
        return source.substring(0, at) + replacement + source.substring(at + original.length());
    }

    private NaturalPromptContentScopeInstructions() { }
}
