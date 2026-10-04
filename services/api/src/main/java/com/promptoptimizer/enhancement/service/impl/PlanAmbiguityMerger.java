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
    private static final Pattern HEADER_GRAMMAR = words("应如何确定", "如何确定", "是否已经确定", "是否已确定",
            "应如何限定", "应如何处理", "如何处理", "应采用哪个口径", "采用哪个口径", "应采用哪种", "计算时", "计算",
            "是否需要", "是否使用", "尚未确定", "未确定", "已经确定", "是什么", "什么", "具体", "需要", "需", "？", "?");
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
            String explanation = decision.answer().matches("(?i)^(?:暂不确定|尚未确定|待定|不知道|不清楚|unknown|tbd)[。.!！]?$")
                    ? "" : " 用户说明：" + decision.answer();
            registered.putIfAbsent(key, "该问题尚未确定：" + decision.question() + explanation);
        }
        for (String text : findings) {
            if (nonDecisionStatement(text)) continue;
            if (registered.containsValue(text)) continue;
            if (serverFindings.contains(text) && text.startsWith("资料对“")) continue;
            if (mergeExistingExplanation(text, registered)) continue;
            // 只有同一字段、双方来源和取值，且不增加业务条件，才能归入已有冲突。
            if (knownConflicts.stream().anyMatch(identity -> identity.isReminder(text)
                    && (registered.containsKey(identity.key()) || decisions.resolvesConflict(
                    identity.field(), List.of(identity.leftValue(), identity.rightValue()))))) continue;
            if (matchesBoundQuestion(text, references)) continue;
            if (mergeConflictExplanation(text, knownConflicts, registered)) continue;
            if (mergeUnresolvedExplanation(text, registered)) continue;
            registered.putIfAbsent("text:" + text, stripDecisionMetadata(text));
        }
        // 全半角、空白和句末问号不是新决定；比较符、完整条件及代码大小写仍须区分。
        Map<String, String> formatted = new LinkedHashMap<>();
        registered.values().forEach(value -> formatted.putIfAbsent(
                java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                        .replaceAll("\\s+", "").replaceAll("[。？?!！]+$", ""), value));
        return MergeResult.from(List.copyOf(formatted.values()));
    }

    /** 支持已经归并过的提醒再次进入组装，完整题目和答案前缀必须逐字一致。 */
    private boolean mergeExistingExplanation(String text, Map<String, String> registered) {
        for (var entry : registered.entrySet()) {
            String prefix = entry.getValue() + " 补充说明：";
            if (text.startsWith(prefix)) {
                String extra = text.substring(prefix.length());
                if (extra.matches("(?s).*(?:另外|此外|还需|还需要|另需|同时还).*(?:确认|决定|是否|选择).*")) return false;
                registered.put(entry.getKey(), text);
                return true;
            }
        }
        return false;
    }

    /** 同一对已登记证据的复述附在原冲突下，其他新来源、取值或条件保持独立。 */
    private boolean mergeConflictExplanation(String text, List<ConflictIdentity> conflicts, Map<String, String> registered) {
        int boundary = text.indexOf('。');
        if (boundary < 0) return false;
        String header = text.substring(0, boundary);
        List<String> matching = conflicts.stream().filter(identity -> registered.containsKey(identity.key()))
                .filter(identity -> identity.repeatsEvidence(header)).map(ConflictIdentity::key).distinct().toList();
        if (matching.size() != 1) return false;
        String detail = text.substring(boundary + 1).strip();
        // 解释若另外发起一项决定（如退款订单适用范围），保留独立提醒，不能折入旧取值冲突。
        if (detail.matches("(?s).*(?:另外|此外|还需|还需要|另需|同时还).*(?:确认|决定|是否|选择).*")) return false;
        String key = matching.getFirst();
        if (!detail.isBlank() && !registered.get(key).contains(detail)) {
            registered.put(key, registered.get(key) + " 补充说明：" + detail);
        }
        return true;
    }

    /**
     * 同一未决问题的展开说明放入原项，新增条件全文保留；已解决问题不走此分支。
     * 必须唯一匹配解释的完整主题句，不使用相似度或模型自报 ID 推断对应关系。
     */
    private boolean mergeUnresolvedExplanation(String text, Map<String, String> registered) {
        String cleaned = stripDecisionMetadata(text);
        // 仅把明确未定题干后的逗号看作解释边界；数量、版本和题干自身的逗号仍属于原题。
        var separator = Pattern.compile("[。；;：:\\r\\n]|(?<=尚未确定)[，,]").matcher(cleaned);
        if (!separator.find()) return false;
        String header = cleaned.substring(0, separator.start()).strip();
        // 明确列出的候选项是同一未决选择的展开，完整保留它们，不把候选取值当作已确认事实。
        var alternatives = Pattern.compile("^(.{2,40}?)(?:应)?采用(.+?)还是(.+?)尚未确定$").matcher(header);
        String matchingHeader = alternatives.matches() ? alternatives.group(1) : header;
        List<ConfirmedPlanDecision> matches = decisions.decisions().stream()
                .filter(decision -> decision.scope() == Scope.UNRESOLVED && conflict(decision.question()).isEmpty())
                .filter(decision -> sameQuestionReminder(header, decision.question())
                        || questionHeader(matchingHeader).equals(questionHeader(decision.question()))
                        || matchesPartialAnswerExplanation(header, decision))
                .toList();
        if (matches.size() != 1 || questionHeader(header).length() < 4) return false;
        ConfirmedPlanDecision decision = matches.getFirst();
        String key = "question:" + (decision.questionId() == null ? decision.question() : decision.questionId());
        String explanation = cleaned.substring(separator.end()).strip();
        if (explanation.matches("(?s).*(?:另外|此外|还需|还需要|另需|同时还).*(?:确认|决定|是否|选择).*")) return false;
        String detail = alternatives.matches() || matchesPartialAnswerExplanation(header, decision) ? cleaned : explanation;
        // 只去掉已经匹配的重复题干，解释中任何新单位、版本或条件仍进入同一条完整执行前提。
        if (!detail.isBlank() && !registered.get(key).contains(detail)) {
            registered.put(key, registered.get(key) + " 补充说明：" + detail);
        }
        return true;
    }

    /**
     * 部分确定的回答可补充原题未写出的待定子项。只有题干中的全部实词可在原题和回答中定位时才归并，
     * 不使用相似度阈值；整条提醒仍保留，新的数值、对象或独立决定不会被删掉。
     */
    private boolean matchesPartialAnswerExplanation(String header, ConfirmedPlanDecision decision) {
        if (decision.answer().length() <= 8) return false;
        String heading = withoutExamples(header.replaceAll("[（(]用户回答[^）)]*[）)]", ""));
        String grammar = "除已确认的|已明确为|保持不变|尚未确定|未确定|其余|具体|是否还需要|是否需要|需要|尚未明确|未明确|未提供|仍待确定|已确认|仅确认|的|中|外|与|和|及";
        String[] fragments = heading.toLowerCase(Locale.ROOT).replaceAll(grammar, " ")
                .split("[^\\p{IsHan}a-z0-9_.<>≤≥=]+|(?<=[\\p{IsHan}])(?=[a-z0-9])|(?<=[a-z0-9])(?=[\\p{IsHan}])");
        String known = normalize(decision.question() + " " + decision.answer());
        int semanticLength = 0;
        for (String fragment : fragments) {
            if (fragment.isBlank()) continue;
            semanticLength += fragment.length();
            if (!fragment.matches("[\\p{IsHan}]+")) {
                if (!Pattern.compile("(?<![a-z0-9_.<>≤≥=])" + Pattern.quote(fragment) + "(?![a-z0-9_.<>≤≥=])")
                        .matcher(known).find()) return false;
                continue;
            }
            // 完整短语可直接命中；重排的中文短语需每一段至少两个字且全部有出处。
            boolean[] covered = new boolean[fragment.length() + 1];
            covered[0] = true;
            for (int start = 0; start < fragment.length(); start++) {
                if (!covered[start]) continue;
                for (int end = start + 2; end <= fragment.length(); end++) {
                    if (known.contains(fragment.substring(start, end))) covered[end] = true;
                }
            }
            if (!known.contains(fragment) && !covered[fragment.length()]) return false;
        }
        return semanticLength >= 6;
    }

    /** 仅消除询问语法，不归一化金额、对象或条件；斜线只在“年龄权重/贴现”固定同主题表达中处理。 */
    private String questionHeader(String text) {
        // “在 X 未定的情况下应如何处理”与“X 尚未确定”的解释可归在同一未决项下。
        // 只取完整 X；数字、版本、范围或复合条件留在 X 中，不凭主题词子串归并。
        var contextual = Pattern.compile("^在([^，,。；;]{2,80}?)(?:尚未确定|未确定)的情况下[，,].*应如何处理[^？?]+[？?]$")
                .matcher(text);
        if (contextual.matches()) text = contextual.group(1);
        text = text.replace("用什么指标衡量", "衡量指标");
        return normalize(HEADER_GRAMMAR.matcher(text).replaceAll(""))
                .replace("参考寿命表来源", "参考寿命表")
                .replace("年龄权重/贴现", "年龄权重和贴现")
                .replace("与", "和").replace("的", "");
    }

    /** 只剔除可核对的纯绑定元数据括注，含其他业务描述的括注原样保留。 */
    private String stripDecisionMetadata(String text) {
        String result = text.replaceAll("[（(]用户回答[：:]?[“\"]?暂不确定[”\"]?[）)]", "");
        for (ConfirmedPlanDecision decision : decisions.decisions()) {
            if (decision.questionId() == null) continue;
            if (decision.scope() == Scope.UNRESOLVED) {
                result = result.replaceAll("[（(](?:confirmedDecisions|planAnswers)\\s*中\\s*"
                        + Pattern.quote(decision.questionId()) + "\\s*为[“\"]暂不确定[”\"][）)]", "");
            }
            // 有实际业务说明时仅替换内部绑定名，不能丢掉括注中的已确认范围与仍未知条件。
            result = result.replaceAll("(?:confirmedDecisions|planAnswers)\\s*中\\s*"
                    + Pattern.quote(decision.questionId()) + "(?![A-Za-z0-9_-])", "用户回答");
        }
        return result;
    }

    /** 优先检查模型引用，缺失或引用不正确时只接受唯一的保守文本匹配。 */
    private boolean matchesBoundQuestion(String text, List<AmbiguityReference> references) {
        List<ConfirmedPlanDecision> matching = decisions.decisions().stream()
                .filter(decision -> conflict(decision.question()).isEmpty())
                .filter(decision -> sameQuestionReminder(text, decision.question())
                        || PlanDecisionIdentity.repeatsDecisionLabel(text, decision.question(), decision.answer())).toList();
        if (matching.size() == 1) return true;
        return references.stream().filter(reference -> reference.message().equals(text))
                .anyMatch(reference -> decisions.decisions().stream().anyMatch(decision ->
                        reference.questionId().equals(decision.questionId())
                                && (matching.contains(decision) || genericReferencedReminder(text, decision.question()))));
    }

    /** 只剔除完全不含业务对象的列表引导语和明确的空列表声明；后续具体缺口始终保留。 */
    private boolean nonDecisionStatement(String text) {
        return text.matches("^(?:无|没有|无待确认事项|没有待确认事项)[。.!！]?$|^无[。]本题必要的事实、范围、读者和交付形式均已提供，不存在影响任务目标且目前缺失的业务决定[。]?$")
                || text.matches("^(?:以下|下列)(?:事项|问题|条件)尚未(?:决定|确定)[，,](?:须保留为预注册前待确认条件[，,])?不得默认补全[：:]$");
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
        if (PlanDecisionIdentity.repeatsReminder(candidate, original)) return true;
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
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s，。；：？、“”‘’（）()?;,:\"']+", "")
                .replaceAll("!(?!=)", "");
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
        /** 每个来源与取值都须出现，去除证据后只允许剩下简单询问语法。 */
        boolean repeatsEvidence(String header) {
            String remaining = normalize(header);
            for (String part : List.of(field, leftPath, rightPath, leftValue, rightValue)) {
                String value = normalize(part);
                if (!remaining.contains(value)) return false;
                remaining = remaining.replace(value, "");
            }
            remaining = remaining.replace("本次订单审批", "").replace("订单审批", "").replace("哪个", "");
            return CONFIRMATION.matcher(remaining).replaceAll("").isEmpty();
        }

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

    /** 展示列表有上限，复制正文使用完整归并结果，不能让展示预算丢掉执行前提。 */
    record MergeResult(List<String> messages, int omittedCount, List<String> executionPrerequisites) {
        static MergeResult from(List<String> values) {
            List<String> complete = values.stream().distinct().toList();
            return new MergeResult(complete.stream().limit(DISPLAY_LIMIT).toList(),
                    Math.max(0, complete.size() - DISPLAY_LIMIT), complete);
        }
    }
}
