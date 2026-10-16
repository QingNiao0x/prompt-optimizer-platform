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

    private TaskConstraintCoverage() { }

    /** 仅省去完整当前断言已覆盖的规则，不跨段拼接部分要求或借用示例、条件标题。 */
    static List<String> uncovered(String content, List<String> rules) {
        Set<String> statements = currentStatements(content);
        return rules.stream().distinct().filter(rule -> rule.lines().count() > 1
                // 多行来源契约沿用既有逐字覆盖，不将完整来源块拆成可互相借用的单句。
                ? !content.contains(rule) : !statements.contains(canonical(rule))).toList();
    }

    /** 按完整行保存作用域；无法确定标题是否属于当前任务时，宁可保留原规则。 */
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
            if (currentScope && !value.isBlank()) statements.add(canonical(value));
        }
        return statements;
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
