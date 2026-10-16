package com.promptoptimizer.enhancement.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 保守核对约束段落中的完整任务规则；不改写正文、来源列表或权限执行规则。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class TaskConstraintCoverage {
    private static final Pattern CONDITIONAL_SCOPE = Pattern.compile("如果|若|除非|假如|假设|仅当|只要|只有|否则|例外|除外|但是");
    private static final Pattern LEADING_SCOPE = Pattern.compile("不|未|无|禁止|仅|只|待|但|未来|以后");
    // 只扩展完整句覆盖；同段的作用域、例外或引用可能影响后续句子，不能拆掉后借作当前全局要求。
    private static final Pattern PARAGRAPH_SCOPE = Pattern.compile(
            "如果|假如|若|除非|仅当|只要|只有|否则|但|例外|除外|仅适用|仅针对|仅就|只对|只在"
                    + "|另一|其他(?:研究|任务|项目)|[甲乙丙丁](?:研究|任务)|(?:研究|任务)[甲乙丙丁]"
                    + "|以后|未来方案|未来确认|待确认|示例|例如|例子"
                    + "|不必遵守|无需遵守|不得要求|不要求|不是要求|并非要求|[：:“”‘’\"'`?？<>]"
                    + "|(?:^|[。！？])\\s*(?:请)?(?:引用|原文|假设)"
                    + "|(?:时|前|后|条件下)[，,]");

    private TaskConstraintCoverage() { }

    /** 仅省去完整当前断言已覆盖的规则，不跨段拼接部分要求或借用示例、条件标题。 */
    static List<String> uncovered(String content, List<String> rules) {
        Set<String> statements = currentStatements(content);
        return rules.stream().distinct().filter(rule -> rule.lines().count() > 1
                // 多行来源契约沿用既有逐字覆盖，不将完整来源块拆成可互相借用的单句。
                ? !content.contains(rule) : !statements.contains(canonical(rule))).toList();
    }

    /** 保留整行覆盖，并接收当前段落中可独立核对的完整句；不确定作用域时仍保留原规则。 */
    private static Set<String> currentStatements(String content) {
        Set<String> statements = new HashSet<>();
        String fence = null;
        boolean currentScope = true;
        for (String line : content.lines().toList()) {
            String value = line.strip();
            if (value.startsWith("```") || value.startsWith("~~~")) {
                String marker = value.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
                continue;
            }
            if (fence != null || value.startsWith(">") || value.startsWith("|")
                    || line.startsWith("    ") || line.startsWith("\t")) continue;
            String heading = value.replaceFirst("^#{1,6}\\s+", "").replaceFirst("^\\*\\*(.+?)\\*\\*$", "$1");
            if (heading.endsWith("：") || heading.endsWith(":") || value.startsWith("#")) {
                // 未知标题、未来条件、另一对象及示例块不能凭后续同值冒充当前任务要求。
                currentScope = heading.matches("(?:本任务|本次任务|当前任务|本研究|本次研究|当前研究)[：:]?")
                        || currentScope && heading.matches("(?:约束|约束条件|任务相关约束)[：:]?");
                continue;
            }
            if (currentScope && !value.isBlank()) {
                statements.add(canonical(value));
                addIndependentSentences(statements, value);
            }
        }
        return statements;
    }

    /** 只按中文句号识别完整句，不拆分分号、数值、路径或跨行残句，也不裁剪原段落及其来源。 */
    private static void addIndependentSentences(Set<String> statements, String paragraph) {
        if (PARAGRAPH_SCOPE.matcher(paragraph).find()) return;
        for (String sentence : paragraph.split("(?<=。)")) {
            if (sentence.endsWith("。")) statements.add(canonical(sentence));
        }
    }

    /** 只合并纯中文自然语言的逗号／分号；标识、引号、数值、比较符及路径逐字比较。 */
    private static String canonical(String value) {
        String statement = value.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
        // 不移除所有标点，也不将英文或引号中的字面量当自然语言分隔符。
        if (!statement.matches("[\\p{IsHan}、，；,;。]+")) return statement;
        int separator = Math.max(Math.max(statement.lastIndexOf('，'), statement.lastIndexOf('；')),
                Math.max(statement.lastIndexOf(','), statement.lastIndexOf(';')));
        if (separator < 0) return statement;
        String leading = statement.substring(0, separator);
        // 逗号/分号可能改变前置否定或条件的作用范围；无法证明独立时逐字保留并继续追加。
        if (CONDITIONAL_SCOPE.matcher(statement).find() || LEADING_SCOPE.matcher(leading).find()
                || leading.matches(".*(?:时|前|后|条件下)$")) return statement;
        return statement.replace('；', '，').replace(';', '，').replace(',', '，');
    }
}
