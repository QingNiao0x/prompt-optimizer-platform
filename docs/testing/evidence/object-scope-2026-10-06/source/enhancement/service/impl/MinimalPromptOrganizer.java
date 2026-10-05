package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.PromptRewriteStrategy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 信息充分且四要素已经明确分段时保留原句，避免为了改写重复扩充交付要求。
 * 不跳过 Provider 校验、资料合并或权限红线；有新增确认选择、未识别标题时沿用模型组织。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class MinimalPromptOrganizer {
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)[：:]?$");
    private static final List<PromptSectionType> REQUIRED = List.of(PromptSectionType.BACKGROUND,
            PromptSectionType.TASK, PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS);

    private MinimalPromptOrganizer() { }

    /** 仅替换四个原文完整覆盖的段落；每行必须保留，模型发现的真实歧义继续走统一归并。 */
    static boolean organize(Map<PromptSectionType, PromptSection> sections, String raw,
                            ConfirmedDecisionSet decisions) {
        if (!PromptRewriteStrategy.forPrompt(raw).mode().equals("MINIMAL_ORGANIZATION")
                || !decisions.knownDecisions().isEmpty()) return false;
        var content = new EnumMap<PromptSectionType, StringBuilder>(PromptSectionType.class);
        PromptSectionType current = PromptSectionType.TASK;
        boolean inCode = false;
        for (String line : raw.lines().toList()) {
            if (line.strip().startsWith("```")) inCode = !inCode;
            var heading = HEADING.matcher(line.strip());
            if (!inCode && heading.matches()) {
                String title = heading.group(1);
                PromptSectionType type = type(title);
                if (type == null) return false;
                current = type;
                content.computeIfAbsent(current, ignored -> new StringBuilder());
                // 业务规则与边界的子标题承载范围，不删掉后让不同对象共用一份规则。
                if (!List.of("背景", "已知资料", "任务", "输出", "交付", "约束").contains(title)) {
                    content.get(current).append("### ").append(title).append('\n');
                }
            } else content.computeIfAbsent(current, ignored -> new StringBuilder()).append(line).append('\n');
        }
        if (inCode || REQUIRED.stream().anyMatch(type -> !content.containsKey(type) || content.get(type).toString().isBlank())) return false;
        for (PromptSectionType type : REQUIRED) {
            PromptSection original = sections.get(type);
            sections.put(type, new PromptSection(type, original.title(), content.get(type).toString().strip()));
        }
        return true;
    }

    /** 仅明确的四要素标题与业务规则子标题可整理，未知章节不自动猜测用途。 */
    private static PromptSectionType type(String title) {
        return switch (title) {
            case "背景", "已知资料" -> PromptSectionType.BACKGROUND;
            case "任务", "任务目标" -> PromptSectionType.TASK;
            case "输出", "交付" -> PromptSectionType.OUTPUT;
            case "约束", "约束与验收", "当前有效规则", "分支与边界说明", "尚待明确" -> PromptSectionType.CONSTRAINTS;
            default -> null;
        };
    }
}
