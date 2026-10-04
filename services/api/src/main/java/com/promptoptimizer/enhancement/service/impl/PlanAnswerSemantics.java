package com.promptoptimizer.enhancement.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 保守识别用户回答中的当前未决部分；历史叙述、条件分支和“不确定性”术语不代表待定。
 * 保留原回答供最终正文核对，只用明确分开的肯定子句参与二次检索和决定补齐。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanAnswerSemantics {
    private static final Pattern PENDING = Pattern.compile("(?i)不确定(?!性)|暂未确定|(?:仍未|尚未|未)(?:确定|确认|提供|指定|决定)"
            + "|待确定|待定|不知道|不清楚|稍后确认|\\b(?:unknown|tbd)\\b");
    private static final Pattern BOUNDARY = Pattern.compile("(?<=[。；;！？!])|\\R|[,，](?=(?:但|不过|然而|现在明确|目前明确))");

    private PlanAnswerSemantics() { }

    /** 任一当前未决子句都使整题保持未完成；不能因前半句确定了工具就消除未知版本。 */
    static boolean unresolved(String answer) {
        return clauses(answer).stream().anyMatch(PlanAnswerSemantics::pendingClause);
    }

    /** 待定开头的解释整体保留为未决说明；混合回答只提取明确分开的已确定部分。 */
    static String confirmedPart(String answer) {
        if (answer == null) return "";
        List<String> clauses = clauses(answer);
        if (clauses.isEmpty() || pendingClause(clauses.getFirst())) return "";
        return clauses.stream().filter(clause -> !pendingClause(clause))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /** 只有出现明确的当前选择才移开历史未知前缀；按完整语句和转折分开，保留条件与数值。 */
    private static List<String> clauses(String answer) {
        if (answer == null || answer.isBlank()) return List.of();
        String current = answer.strip();
        var historical = Pattern.compile("^(?:之前|此前|原先|过去|先前|原本)[^，,。；;]*(?:不确定|未确定)[，,。；;]((?:现在|目前|本次|现已)(?:已)?(?:明确|确认|确定|选择|采用).+)$")
                .matcher(current);
        if (historical.matches()) current = historical.group(1);
        return Arrays.stream(BOUNDARY.split(current)).map(String::strip)
                .filter(value -> !value.isEmpty()).toList();
    }

    /** 只认当前状态断言；明确的历史转折和否认未决事项不反向变成新的问题。 */
    private static boolean pendingClause(String clause) {
        if (!PENDING.matcher(clause).find()) return false;
        // “未提供的工程细节先核查”是对未展示实现的核查指令，不是撤销前面已选定的业务值。
        // 只接受这个明确的条件化工程表达；具体版本、生效时间或业务口径未定仍属于未决部分。
        if (clause.matches("(?i)^(?:未提供|未明确)的(?:现有|具体)?工程(?:实现)?细节(?:先|需|应|由执行\\s*agent先).*(?:核查|核对).*")) return false;
        if (clause.matches("^(?:若|如果|假如|假设).*") || clause.matches("^当(?!前).*(?:则|就|时).*")) return false;
        if (clause.matches(".*(?:未确认|未确定|未提供)(?:前|时|之前|的情况下).*(?:不得|不能|不应|禁止|先|应|需).*")) return false;
        if (clause.matches("^(?:没有|不存在|并无)(?:尚未确定|未确定|待定).*(?:事项|问题|选择)[。；;]?$")) return false;
        return true;
    }
}
