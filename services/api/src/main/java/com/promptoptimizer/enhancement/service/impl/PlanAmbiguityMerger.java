package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import com.promptoptimizer.provider.domain.AmbiguityReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 按本次绑定问题与冲突证据登记提醒，再合并模型补充；不改变索引、提问或确认答案。
 * 无法证明为同一提醒的文本继续保留，展示限额只在归并结束后应用。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanAmbiguityMerger {
    private static final int DISPLAY_LIMIT = 8;
    private static final Pattern CONFLICT = Pattern.compile(
            "资料对“([^”]+)”存在不同取值：(.+?)（([^）]+)）与 (.+?)（([^）]+)）");
    private static final Pattern EXAMPLE = Pattern.compile("[（(](?:如|例如|比如)[^（）()]*[）)]");
    private static final Pattern CONFIRMATION = words("该问题尚未确定", "存在不同取值", "冲突未解决",
            "尚未确认", "尚未确定", "尚未明确", "尚未提供", "未确认", "未确定", "未明确", "未提供",
            "未指定", "暂不确定", "未知", "冲突", "规定", "取值", "确认答案为", "答案为", "本次",
            "用户", "回答", "仍然", "仍", "需要", "必须", "已确认但", "已确认", "核对", "阈值", "需",
            "请", "确认", "明确", "采用", "哪一项", "为", "与");
    private static final Pattern QUESTION_GRAMMAR = words("该问题尚未确定", "尚未确认", "尚未明确", "尚未提供",
            "尚未确定", "未确认", "未明确", "未提供", "未指定", "未确定", "未知", "暂不确定",
            "从哪里获取", "具体", "主要", "本次", "这项", "需要", "哪些", "哪个", "什么", "是否",
            "请", "说明", "明确", "提供", "指定", "确认", "包括", "覆盖", "进行", "研究", "分析", "数据", "的", "是");
    private final ConfirmedDecisionSet decisions;

    PlanAmbiguityMerger(ConfirmedDecisionSet decisions) {
        this.decisions = decisions;
    }

    /** 新冲突优先，其次为原始未决问题；外部引用不能单独触发删除。 */
    MergeResult merge(List<String> findings, List<String> serverFindings, List<AmbiguityReference> references) {
        Map<String, String> registered = new LinkedHashMap<>();
        List<ConflictIdentity> knownConflicts = new ArrayList<>();
        for (String text : serverFindings) {
            if (!text.startsWith("资料对“")) continue;
            var identity = conflict(text);
            identity.ifPresent(knownConflicts::add);
            registered.putIfAbsent(identity.map(ConflictIdentity::key).orElse("text:" + text), text);
        }
        for (ConfirmedPlanDecision decision : decisions.decisions()) {
            var identity = conflict(decision.question());
            if (decision.questionId() != null && decision.questionId().startsWith("context-conflict-")) {
                identity.ifPresent(knownConflicts::add);
            }
            if (decision.scope() != Scope.UNRESOLVED) continue;
            String key = identity.map(ConflictIdentity::key).orElse("question:"
                    + (decision.questionId() == null ? decision.question() : decision.questionId()));
            registered.putIfAbsent(key, "该问题尚未确定：" + decision.question());
        }
        for (String text : findings) {
            if (serverFindings.contains(text) && text.startsWith("资料对“")) continue;
            // 只有同一字段、双方来源和取值，且不增加业务条件，才能归入已有冲突。
            if (knownConflicts.stream().anyMatch(identity -> identity.isReminder(text)
                    && (registered.containsKey(identity.key()) || decisions.resolvesConflict(
                    identity.field(), List.of(identity.leftValue(), identity.rightValue()))))) continue;
            if (matchesBoundQuestion(text, references)) continue;
            registered.putIfAbsent("text:" + text, text);
        }
        List<String> values = registered.values().stream().distinct().toList();
        return new MergeResult(values.stream().limit(DISPLAY_LIMIT).toList(),
                Math.max(0, values.size() - DISPLAY_LIMIT));
    }

    /** 优先检查模型引用，缺失或引用不正确时只接受唯一的保守文本匹配。 */
    private boolean matchesBoundQuestion(String text, List<AmbiguityReference> references) {
        List<ConfirmedPlanDecision> matching = decisions.decisions().stream()
                .filter(decision -> conflict(decision.question()).isEmpty())
                .filter(decision -> sameQuestionReminder(text, decision.question())).toList();
        if (matching.size() == 1) return true;
        return references.stream().filter(reference -> reference.message().equals(text))
                .anyMatch(reference -> decisions.decisions().stream().anyMatch(decision ->
                        reference.questionId().equals(decision.questionId())
                                && (matching.contains(decision) || genericReferencedReminder(text, decision.question()))));
    }

    /** 未列入常见维度的题目只在有效 ID 和完整剩余主题同时吻合时合并，不凭 ID 清空内容。 */
    private boolean genericReferencedReminder(String text, String question) {
        List<String> clauses = reminderClauses(withoutExamples(text));
        if (clauses.size() > 1) {
            return clauses.stream().allMatch(clause -> genericReferencedReminder(clause, question));
        }
        String candidate = normalize(QUESTION_GRAMMAR.matcher(withoutExamples(text)).replaceAll(""));
        String known = normalize(QUESTION_GRAMMAR.matcher(withoutExamples(question)).replaceAll(""));
        return candidate.length() >= 4 && known.contains(candidate);
    }

    /**
     * 兼容旧 Provider 的自然语言提醒。只消除询问语法和可选示例，剩余内容必须已在原题中出现。
     * 同主题的新金额、版本、对象、条件和第二个决定不能仅凭主题命中而被合并。
     */
    private boolean sameQuestionReminder(String text, String question) {
        String candidate = withoutExamples(text);
        String original = withoutExamples(question);
        if (normalize(candidate).equals(normalize(original))) return true;
        // 每个完整子句都必须有已知依据；不能全局删除“是否/需要/提供/数据”后吞掉第二个问题。
        List<String> clauses = reminderClauses(candidate);
        if (clauses.size() > 1) {
            return clauses.stream().allMatch(clause -> sameQuestionReminder(clause, original));
        }
        List<QuestionAspect> aspects = Arrays.stream(QuestionAspect.values())
                .filter(aspect -> aspect.pattern.matcher(original).find()).toList();
        if (aspects.size() != 1 || !aspects.getFirst().pattern.matcher(candidate).find()) return false;
        Pattern aspect = aspects.getFirst().pattern;
        String remaining = residue(candidate, aspect);
        String known = residue(original, aspect);
        return remaining.isEmpty() || !known.isEmpty() && known.contains(remaining);
    }

    /** 逐句和分句检查；保留数字之间的逗号、小数点及比较符，不能改变金额或版本。 */
    private List<String> reminderClauses(String text) {
        return Arrays.stream(text.split("[。；;！？?\\r\\n]+|(?<![0-9])[,，]|[,，](?![0-9])"))
                .map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    private String residue(String text, Pattern aspect) {
        return normalize(QUESTION_GRAMMAR.matcher(aspect.matcher(text).replaceAll("")).replaceAll(""));
    }

    /** 例子只在没有额外询问或强制条件时视为填写辅助，不能隐藏括号中的新要求。 */
    private String withoutExamples(String text) {
        return EXAMPLE.matcher(text).replaceAll(match ->
                match.group().matches("(?s).*(是否|必须|仅|不得|适用|除外|但是|冲突|[？?]).*")
                        ? java.util.regex.Matcher.quoteReplacement(match.group()) : "");
    }

    /** 只从平台冲突格式提取成对证据，不把模型自报的字段名视为已核验冲突。 */
    private Optional<ConflictIdentity> conflict(String text) {
        var match = CONFLICT.matcher(text);
        if (!match.find()) return Optional.empty();
        return Optional.of(new ConflictIdentity(match.group(1), match.group(2), match.group(3),
                match.group(4), match.group(5)));
    }

    private static String normalize(String text) {
        // 比较符、版本小数点和代码标识符不是排版符号，不能归一化掉。
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s，。；：！？、“”‘’（）()!?;,:\"']+", "");
    }

    /** 先匹配长词，避免短词破坏完整确认短语。 */
    private static Pattern words(String... values) {
        return Pattern.compile(Arrays.stream(values).sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|")));
    }

    private enum QuestionAspect {
        DATA_SOURCE("数据来源|数据源|来源|从哪里获取"),
        DEFINITION("死亡率定义|具体定义|定义"),
        TIME_RANGE("时间范围|时间段|年份区间"),
        DISEASE_SCOPE("病种范围|疾病范围|病种"),
        PURPOSE("分析目的|(?<!项)目的"),
        REGION("研究范围|地区范围|地区|地域"),
        TOOL("分析工具|工具"),
        OUTPUT_FORMAT("输出格式|交付格式"),
        AUTHENTICATION("认证方式|登录方式|身份保持方式");

        private final Pattern pattern;
        QuestionAspect(String expression) { this.pattern = Pattern.compile(expression); }
    }

    /** 来源与取值成对排序，反向引用相同证据仍是同一冲突，不同取值/来源另行保留。 */
    private record ConflictIdentity(String field, String leftPath, String leftValue,
                                    String rightPath, String rightValue) {
        String key() {
            return "conflict:" + field + ":" + List.of(leftPath + "\u0000" + leftValue,
                    rightPath + "\u0000" + rightValue).stream().sorted().toList();
        }

        boolean isReminder(String text) {
            String remainder = text;
            for (String component : List.of(leftPath, rightPath, leftValue, rightValue, field)) {
                if (!remainder.contains(component)) return false;
                remainder = remainder.replace(component, "");
            }
            // 这里只识别阈值未决的说明句；新增金额、退款授权或其他范围仍保留在剩余内容中。
            remainder = normalize(remainder).replaceAll(
                    "(?:该值直接(?:影响|决定)|否则(?:无法|不能)确定)(?:审批|财务复核)的?触发条件(?:和财务复核范围)?", "");
            remainder = remainder.replace("资料对存在不同取值", "").replace("该问题尚未确定", "");
            return CONFIRMATION.matcher(remainder).replaceAll("").isEmpty();
        }
    }

    record MergeResult(List<String> messages, int omittedCount) { }
}
