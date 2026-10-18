package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.policy.domain.PlatformConstraintRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 固定条款由服务端组装。只归一化完整固定行，保留引文、代码、表格及用户自行声明的业务边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlatformConstraintRenderer {
    private PlatformConstraintRenderer() { }

    /** 再次增强时旧平台标题内的固定条款不是新业务指令；标题外的用户限制原样保留。 */
    static String taskInput(String text) {
        return strip(text, Set.of(), Set.copyOf(PlatformConstraintRules.CODE_RULES), true);
    }

    /** 清理模型重复的固定条款；用户原始需求中独立指定的相同条款仍作为任务限制保留。 */
    static String providerText(String text, String rawPrompt, ConstraintBundle constraints) {
        Set<String> explicit = taskInput(rawPrompt).lines().map(PlatformConstraintRenderer::ruleText)
                .filter(PlatformConstraintRules.CODE_RULES::contains).collect(Collectors.toSet());
        Set<String> owned = java.util.stream.Stream.concat(PlatformConstraintRules.CODE_RULES.stream(),
                constraints.visibleConstraints().stream()).collect(Collectors.toSet());
        return strip(text, explicit, owned, false);
    }

    /** 固定规则最后落笔，以独立短列表融入约束正文，避免普通压缩器改变数量、顺序或原文。 */
    static String appendMandatory(String text, ConstraintBundle constraints) {
        String block = "- " + String.join("\n- ", constraints.platformMandatory());
        text = removeUnscopedMandatorySentences(text, Set.copyOf(constraints.platformMandatory()));
        if (text.isBlank()) return block;
        // 末尾条件或具名章节不能让全局规则继承局部范围；仅此时用普通约束标题重置。
        boolean scoped = hasTrailingScopedBlock(text);
        String last = text.lines().filter(line -> !line.isBlank()).reduce((left, right) -> right).orElse("");
        String separator = isHeading(last.strip()) && isGeneralConstraintHeading(last) ? "\n" : "\n\n";
        return text.stripTrailing() + separator + (scoped ? "约束条件：\n" : "") + block;
    }

    /** 固定规则可嵌在一行多句中；只移除完整等价句，引用、代码、表格和具名范围不参与。 */
    private static String removeUnscopedMandatorySentences(String text, Set<String> mandatory) {
        List<String> lines = new ArrayList<>();
        boolean protectedScope = false;
        String fence = null;
        for (String line : text.lines().toList()) {
            String value = line.strip();
            if (value.startsWith("```") || value.startsWith("~~~")) {
                String marker = value.substring(0, 3);
                fence = fence == null ? marker : fence.equals(marker) ? null : fence;
                lines.add(line);
                continue;
            }
            if (fence != null || value.startsWith("|") || value.startsWith(">")) { lines.add(line); continue; }
            if (isHeading(value)) {
                protectedScope = !isGeneralConstraintHeading(value);
                lines.add(line);
                continue;
            }
            if (protectedScope) { lines.add(line); continue; }
            StringBuilder kept = new StringBuilder();
            var sentences = java.util.regex.Pattern.compile("[^。]+。?").matcher(line);
            while (sentences.find()) {
                String sentence = sentences.group();
                if (!mandatory.contains(ruleText(sentence))) kept.append(sentence);
            }
            if (!kept.toString().isBlank()) lines.add(kept.toString());
        }
        return String.join("\n", lines).strip();
    }

    /**
     * 只删除完整等价行；旧平台块中不认识的规则改为任务规则，不能随标题整块丢弃。
     * fenced code、引用和表格从不作为可删除条款处理。
     */
    private static String strip(String text, Set<String> preserve, Set<String> owned, boolean onlyPlatformBlock) {
        List<String> output = new ArrayList<>();
        String fence = null;
        boolean platform = false;
        boolean protectedScope = false;
        boolean needsTaskHeading = false;
        for (String line : (text == null ? "" : text).split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String marker = trimmed.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
                output.add(line);
                continue;
            }
            if (fence != null || trimmed.startsWith(">") || trimmed.startsWith("|")) {
                output.add(line);
                continue;
            }
            String rule = ruleText(line);
            if (rule.equals(PlatformConstraintRules.HEADING)) {
                platform = true;
                protectedScope = false;
                needsTaskHeading = true;
                continue;
            }
            boolean heading = isHeading(trimmed);
            if (heading) {
                platform = false;
                // 具名业务或条件章节内的完整同句仍有独立作用域，不能当成平台复读清除。
                protectedScope = !isGeneralConstraintHeading(rule);
                needsTaskHeading = false;
            }
            if ((!onlyPlatformBlock || platform) && !protectedScope && owned.contains(rule)
                    && !preserve.contains(rule)) continue;
            if (platform && needsTaskHeading && !trimmed.isBlank()) {
                output.add("任务相关约束：");
                needsTaskHeading = false;
            }
            output.add(line);
        }
        return String.join("\n", output).trim();
    }

    /** 仅移除行首列表和包围整行的排版标记，不移除路径中的星号或规则内部符号。 */
    private static String ruleText(String line) {
        String value = line.trim().replaceFirst("^(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)、]\\s*)", "");
        if (value.startsWith("**") && value.endsWith("**") && value.length() > 4) value = value.substring(2, value.length() - 2);
        return value.trim();
    }

    /** 仅识别明确分节标记；正文中的条件原句不会被当作平台来源标识。 */
    private static boolean isHeading(String value) {
        String rule = ruleText(value);
        return value.startsWith("#") || rule.endsWith("：") || rule.endsWith(":");
    }

    /** 普通约束标题只界定全局呈现范围，不授予删除其他规则或访问文件的权限。 */
    private static boolean isGeneralConstraintHeading(String value) {
        return Set.of("约束", "约束条件", "任务相关约束", "推断依据")
                .contains(ruleText(value).replaceFirst("[：:]$", ""));
    }

    /** 追踪最后一个明确章节的范围，引用、代码和表格内的排版不改变正文范围。 */
    private static boolean hasTrailingScopedBlock(String text) {
        boolean scoped = false;
        String fence = null;
        for (String line : text.lines().toList()) {
            String value = line.strip();
            if (value.startsWith("```") || value.startsWith("~~~")) {
                String marker = value.substring(0, 3);
                fence = fence == null ? marker : fence.equals(marker) ? null : fence;
                continue;
            }
            if (fence != null || value.startsWith(">") || value.startsWith("|")) continue;
            if (isHeading(value)) scoped = !isGeneralConstraintHeading(value);
        }
        return scoped;
    }
}
