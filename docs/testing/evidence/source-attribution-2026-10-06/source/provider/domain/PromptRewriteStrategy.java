package com.promptoptimizer.provider.domain;

import java.util.regex.Pattern;

/**
 * 为信息充分的需求选择最小整理策略；判断只影响改写幅度，不跳过模型、安全或保真校验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptRewriteStrategy(String mode, String instruction) {
    private static final Pattern OUTPUT = Pattern.compile("(?m)^#{1,6}\\s*(?:输出|交付)|(?:只|仅)(?:输出|交付|提供)");
    private static final Pattern CONTEXT = Pattern.compile("(?m)^#{1,6}\\s*(?:背景|已知|资料|现状)|已明确|已确认|当前|现有");
    private static final Pattern LIMIT = Pattern.compile("不得|不能|不要|必须|保持|仅当|只允许|不生成|不修改");

    /** 固定判断不把字数或测试结果当作充分性；未知问题仍可随原需求保留。 */
    public static PromptRewriteStrategy forPrompt(String raw) {
        String text = raw == null ? "" : raw;
        boolean sufficient = OUTPUT.matcher(text).find() && CONTEXT.matcher(text).find()
                && LIMIT.matcher(text).results().limit(2).count() >= 2;
        return sufficient ? new PromptRewriteStrategy("MINIMAL_ORGANIZATION",
                "需求已包含目标、交付和边界。只整理四要素、补充实质缺口；尽量沿用有效原句。"
                        + "同一规则或确认答案只出现一次，不再追加逐题问答、全套默认流程或通用验收清单。"
                        + "保留所有明确限制、材料未知及新增冲突；不要为显得更专业而扩大任务。")
                : new PromptRewriteStrategy("CLARIFY_AND_STRUCTURE",
                "按本次目标整理四要素，补充有证据的背景和必要边界，未知决定保持未知。"
                        + "常规执行细节交给执行者；禁止把建议、默认选项或示例写成已确认事实。");
    }
}
