package com.promptoptimizer.enhancement.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * 保守识别用户回答中的当前未决部分；历史叙述、条件分支和“不确定性”术语不代表待定。
 * 保留原回答供最终正文核对，只用明确分开的肯定子句参与二次检索和决定补齐。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanAnswerSemantics {
    private static final Pattern STATUS_ONLY_SUBJECT = Pattern.compile("^(?:当前|目前|现在|本次|这次|此时|现阶段|本阶段)$");
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

    /**
     * 分别保留完整未决子句，不把同一回答里的已定月份、工具或空值规则重新变成未知。
     * 不拆逗号连接的适用条件和禁止猜测说明，也不将条件分支中的“未确定”认作当前待定。
     */
    static List<String> pendingParts(String answer) {
        List<String> pending = clauses(answer).stream().filter(PlanAnswerSemantics::pendingClause)
                .map(PlanAnswerSemantics::namedContinuation).distinct().toList();
        boolean named = pending.stream().anyMatch(PlanAnswerSemantics::namesPendingSubject);
        // “暂不确定。寿命表尚未提供”是一个缺口的总括与展开；不再把总括登记为第二个业务问题。
        return named ? pending.stream().filter(PlanAnswerSemantics::namesPendingSubject).toList() : pending;
    }

    /**
     * “暂不确定，不锁定一致性统计指标”仍命名了独立属性，不能被同回答的评分锚点覆盖。
     * 只接受完整的禁止选定语法；“暂不确定，不得默认补全”等总括不猜测业务主语。
     */
    private static String namedContinuation(String clause) {
        var continuation = Pattern.compile("^(?:暂不确定|尚未确定|当前尚未决定)[，,](?:不锁定|不自行选定)"
                + "([^，,。；;]{2,80})[。；;]?$").matcher(clause);
        return continuation.matches() ? continuation.group(1) + "尚未确定。" : clause;
    }

    /** 有明确业务主语的未决子句可独立展示；裸“暂不确定”仍需继承服务端原题才能保持含义。 */
    static boolean namesPendingSubject(String clause) {
        var pending = pendingMarker(clause);
        if (pending.isEmpty()) return false;
        String subject = clause.substring(0, pending.get().start()).replaceFirst("^(?:但|不过|然而)", "")
                .replaceAll("暂|尚|仍|具体|其余|的|\\s|[，,：:]", "");
        // “当前尚未决定”只有状态，仍须保留服务端原题和 ID；完整命名的业务对象继续按子项处理。
        return subject.length() >= 2 && !STATUS_ONLY_SUBJECT.matcher(subject).matches();
    }

    /** 未决断言前的完整对象与属性；只移除紧邻状态词的程度副词，不删业务名、数值或条件。 */
    static String pendingSubject(String clause) {
        var pending = pendingMarker(clause);
        if (pending.isEmpty()) return "";
        return clause.substring(0, pending.get().start()).strip().replaceFirst("(?:完全|仍然|仍|目前|现在|暂时|暂)$", "");
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
        if (pendingMarker(clause).isEmpty()) return false;
        // “未提供的工程细节先核查”是对未展示实现的核查指令，不是撤销前面已选定的业务值。
        // 只接受这个明确的条件化工程表达；具体版本、生效时间或业务口径未定仍属于未决部分。
        if (clause.matches("(?i)^(?:未提供|未明确)的(?:现有|具体)?工程(?:实现)?细节(?:先|需|应|由执行\\s*agent先).*(?:核查|核对).*")) return false;
        if (clause.matches("^(?:若|如果|假如|假设).*") || clause.matches("^当(?!前).*(?:则|就|时).*")) return false;
        if (clause.matches(".*(?:未确认|未确定|未提供)(?:前|时|之前|的情况下).*(?:不得|不能|不应|禁止|先|应|需).*")) return false;
        if (clause.matches("^(?:没有|不存在|并无)(?:尚未确定|未确定|待定).*(?:事项|问题|选择)[。；;]?$")) return false;
        return true;
    }

    /**
     * UNKNOWN 可为具名数据状态；只有明确说明该代码的处理方式时才跳过英文状态词。
     * 裸 unknown、TBD 和同句的真实未决口径继续生效，业务状态不能掩盖后半句的未知。
     */
    private static Optional<MatchResult> pendingMarker(String clause) {
        var marker = PENDING.matcher(clause);
        while (marker.find()) {
            if (marker.group().equals("UNKNOWN")) {
                String suffix = clause.substring(marker.end());
                if (suffix.matches("(?s)^\\s*(?:不(?:直接)?计(?:为|入)|作为|表示|代表|单独(?:统计|列示|呈现)).*")) continue;
                String prefix = clause.substring(0, marker.start());
                // “到诊状态为UNKNOWN的预约事件”明确引用一个代码，不是用户回答“我不知道”。
                // 具名分母中的代码也不能先于后面的中文“尚未确定”成为未决标记，否则会截掉完整取值。
                if (prefix.matches("(?s).*(?:状态|状态码|代码)\\s*(?:为|是|=)\\s*$")
                        || !prefix.isBlank() && suffix.matches("(?s)^\\s*(?:尚未|仍未|未)(?:确定|确认|决定|提供|指定).*")) continue;
            }
            return Optional.of(marker.toMatchResult());
        }
        return Optional.empty();
    }
}
