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

    /** 平台块最后落笔，避免普通规则压缩器改变固定数量、顺序或原文。 */
    static String appendMandatory(String text, ConstraintBundle constraints) {
        String block = PlatformConstraintRules.HEADING + "\n- " + String.join("\n- ", constraints.platformMandatory());
        return text.isBlank() ? block : text.stripTrailing() + "\n\n" + block;
    }

    /**
     * 只删除完整等价行；旧平台块中不认识的规则改为任务规则，不能随标题整块丢弃。
     * fenced code、引用和表格从不作为可删除条款处理。
     */
    private static String strip(String text, Set<String> preserve, Set<String> owned, boolean onlyPlatformBlock) {
        List<String> output = new ArrayList<>();
        String fence = null;
        boolean platform = false;
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
                needsTaskHeading = true;
                continue;
            }
            boolean heading = trimmed.startsWith("#") || rule.endsWith("：") || rule.endsWith(":");
            if (heading) {
                platform = false;
                needsTaskHeading = false;
            }
            if ((!onlyPlatformBlock || platform) && owned.contains(rule)
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
}
