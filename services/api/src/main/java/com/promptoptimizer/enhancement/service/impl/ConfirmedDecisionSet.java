package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从服务端校验过的问题和回答构造本次决定。作用范围仅用于区分现状和目标，不推断项目事实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ConfirmedDecisionSet {
    private static final Pattern UNRESOLVED = Pattern.compile("(?i)^(?:暂不确定|尚未确定|待定|不知道|不清楚|unknown|tbd)[。.!！]?$|未决定|稍后确认");
    private static final Pattern TOPIC = Pattern.compile("(研究地区|地区范围|数据来源|数据格式|输出格式|交付格式|交付内容|交付物|输出内容|输出方式|分析工具|分析方法|验收标准|统计口径|审批阈值|认证方式|登录方式|技术选型|技术栈|版本|范围|时限|规则|格式|地区|工具|口径|阈值)");
    private static final Pattern TARGET = Pattern.compile("(迁移|改为|替换|新增|目标|期望|希望|想要|计划采用|本次采用|最终采用)");
    private static final Pattern CURRENT = Pattern.compile("(现有|当前|目前|已实现|已经采用|正在使用)");
    private static final Pattern DETAIL = Pattern.compile("(版本|字段|异常|错误|单位|阈值|有效期|过期|格式|来源|刷新|退出|兼容)");
    private static final Pattern CONFLICT_EVIDENCE = Pattern.compile(
            "资料对“([^”]+)”存在不同取值：(.+?)（([^）]+)）与 (.+?)（([^）]+)）");

    private final List<ConfirmedPlanDecision> decisions;

    private ConfirmedDecisionSet(List<ConfirmedPlanDecision> decisions) {
        this.decisions = List.copyOf(decisions);
    }

    /** 仅以服务端规范化回答为输入；客户端问题文案不能成为事实来源。 */
    static ConfirmedDecisionSet from(List<PlanAnswer> answers) {
        if (answers == null || answers.isEmpty()) return new ConfirmedDecisionSet(List.of());
        List<ConfirmedPlanDecision> values = new ArrayList<>();
        for (PlanAnswer answer : answers) {
            if (answer == null || answer.answer() == null || answer.answer().isBlank()) continue;
            String question = answer.question() == null ? "" : answer.question();
            String text = answer.answer().trim();
            String topic = topic(question, answer.questionId());
            Scope scope = UNRESOLVED.matcher(text).find() ? Scope.UNRESOLVED
                    : TARGET.matcher(question + text).find() ? Scope.TARGET
                    : CURRENT.matcher(question).find() ? Scope.CURRENT_STATE : Scope.CHOICE;
            values.add(new ConfirmedPlanDecision(answer.questionId(), question, topic, scope, text));
        }
        return new ConfirmedDecisionSet(values);
    }

    List<ConfirmedPlanDecision> decisions() { return decisions; }

    /** 需求保持主查询，已确认的选项单独加入；不混入问题中的未选候选项。 */
    String retrievalQuery(String rawPrompt) {
        StringBuilder query = new StringBuilder(rawPrompt == null ? "" : rawPrompt.trim());
        for (ConfirmedPlanDecision decision : decisions) {
            if (decision.scope() == Scope.UNRESOLVED) continue;
            query.append('\n').append(decision.topic()).append('：').append(decision.answer());
        }
        return query.toString();
    }

    /** 只有同主题的明确答案才能覆盖“未知”问题；资料冲突交由冲突检测单独判定。 */
    boolean coversUnknown(String finding) {
        if (finding == null || !finding.matches(".*(未知|未明确|未提供|尚未确定|未给出|未指定).*")) return false;
        if (finding.matches(".*(历史|例外|冲突|不一致|迁移后|新增范围).*")) return false;
        return decisions.stream().anyMatch(decision -> decision.scope() != Scope.UNRESOLVED
                && !decision.topic().equals("本次选择") && matchesTopic(finding, decision.topic())
                && (!CURRENT.matcher(finding).find() || decision.scope() == Scope.CURRENT_STATE)
                && DETAIL.matcher(finding).results().allMatch(detail -> decision.question().contains(detail.group())));
    }

    /** 仅去除同文问题；不同措辞或增加边界的问题不能仅凭相似词判定已回答。 */
    boolean repeatsAnsweredQuestion(String finding) {
        String normalized = normalize(finding);
        return normalized.length() >= 8 && decisions.stream().anyMatch(decision ->
                decision.scope() != Scope.UNRESOLVED
                        && normalized.equals(normalize(decision.question())));
    }

    /** 未决答案保留原问题，避免多个未知主题都退化为同一占位警告。 */
    List<String> unresolvedFindings() {
        return decisions.stream().filter(decision -> decision.scope() == Scope.UNRESOLVED)
                .map(decision -> "该问题尚未确定：" + decision.question())
                .distinct().toList();
    }

    /** 冲突题的已选择值必须属于本次已知的两份材料；第三个新取值不能被旧回答掩盖。 */
    boolean resolvesConflict(String field, List<String> values) {
        return decisions.stream().anyMatch(decision -> decision.questionId() != null
                && decision.questionId().startsWith("context-conflict-")
                && decision.topic().equals(field) && decision.scope() != Scope.UNRESOLVED
                && values.stream().allMatch(value -> decision.question().contains("（" + value + "）"))
                && (selectedConflictValue(decision).filter(selected -> values.stream()
                        .anyMatch(value -> normalizeValue(value).equals(selected))).isPresent()
                    || decision.answer().matches("^(?:两份|同时).*(?:保留|写出|标明).*")));
    }

    /** 已绑定冲突题中的明确选择优先与新增证据比较，不能先拿被放弃的旧值构造新冲突。 */
    Optional<String> selectedConflictValue(String field) {
        return decisions.stream().filter(decision -> decision.topic().equals(field))
                .map(this::selectedConflictValue).flatMap(Optional::stream).findFirst();
    }

    /** 仅在服务端问题给定的候选取值中解析明确选择；否定、条件及多个取值均不猜测。 */
    private Optional<String> selectedConflictValue(ConfirmedPlanDecision decision) {
        if (decision.scope() == Scope.UNRESOLVED || decision.questionId() == null
                || !decision.questionId().startsWith("context-conflict-")) return Optional.empty();
        List<String> candidates = conflictValues(decision.question());
        String answer = normalizeValue(decision.answer());
        if (candidates.contains(answer)) return Optional.of(answer);
        List<String> selected = new ArrayList<>();
        for (String clause : answer.split("[，,。；;\\n]")) {
            if (clause.matches("(?s).*(不采用|不用|不选|不要|不以|不能|未确定|如果|假如|可能|或者|还是|是否|待确认|再确认|都可以).*")) continue;
            if (!clause.matches("(?s)^(?:本次|最终|请|决定)?\\s*(?:以|采用|用|使用|选择|选定|按照|按).+")) continue;
            for (String value : candidates) {
                if (containsCompleteValue(clause, value)) selected.add(value);
            }
        }
        List<String> unique = selected.stream().distinct().toList();
        return unique.size() == 1 ? Optional.of(unique.getFirst()) : Optional.empty();
    }

    /** 模型重复旧冲突时核对完整取值，新增冲突或改写到无法匹配时继续显示。 */
    boolean coversConflictMessage(String finding) {
        if (finding == null || conflictKey(finding).isEmpty()) return false;
        var field = Pattern.compile("资料对“([^”]{2,40})”").matcher(finding);
        if (!field.find()) return false;
        List<String> pair = conflictValues(finding);
        return pair.size() == 2 && resolvesConflict(field.group(1), pair);
    }

    /** 同字段、同来源、同取值的提醒合并；新增取值或不同来源保留独立证据。 */
    List<String> uniqueFindings(List<String> findings) {
        var unique = new LinkedHashMap<String, String>();
        for (String finding : findings) {
            String key = conflictKey(finding).orElseGet(() -> unique.keySet().stream()
                    .filter(known -> sameConflictReminder(finding, known)).findFirst().orElse(finding));
            unique.putIfAbsent(key, finding);
        }
        return unique.values().stream().limit(8).toList();
    }

    /** 仅合并包含完整字段、双方来源及取值的确认性改写；剩余业务条件不在白名单时保留。 */
    private boolean sameConflictReminder(String finding, String known) {
        var evidence = CONFLICT_EVIDENCE.matcher(known);
        if (!evidence.matches()) return false;
        String remainder = finding;
        for (int group : List.of(2, 4, 3, 5, 1)) {
            String component = evidence.group(group);
            if (!remainder.contains(component)) return false;
            remainder = remainder.replace(component, "");
        }
        return confirmationReminder(remainder);
    }

    /** 只忽略催促确认的固定用语；尾部新增数字、适用范围或业务条件时保留完整提醒。 */
    private Optional<String> conflictKey(String finding) {
        int start = finding.indexOf("资料对“");
        int end = finding.indexOf("）。", start);
        if (start < 0 || end <= start) return Optional.empty();
        String prefix = normalize(finding.substring(0, start));
        String suffix = normalize(finding.substring(end + 2));
        if (!prefix.isEmpty() && !prefix.equals("该问题尚未确定")) return Optional.empty();
        if (!confirmationReminder(suffix)) {
            return Optional.empty();
        }
        return Optional.of(finding.substring(start, end + 1));
    }

    /** 只折叠确认状态及阈值未决的直接影响；金额、退款范围等新增内容不会被移除。 */
    private boolean confirmationReminder(String text) {
        String remainder = normalize(text).replaceAll(
                "(?:该值直接决定|否则(?:无法|不能)确定)(?:审批|财务复核)的?触发条件(?:和财务复核范围)?", "");
        return remainder.matches("(?:取值冲突|冲突未解决|存在不同取值|为|与|用户|回答|确认答案为|答案为"
                + "|暂不确定|尚未确定|仍未确定|已确认但|已|需|需要|请|确认|明确|采用|哪一项|本次"
                + "|有效值|生效阈值|核对|阈值|实现|作为|生效)*");
    }

    /** 已知主题取短标签；未知主题不用于自动消除歧义，也不猜测分类。 */
    private static String topic(String question, String id) {
        var field = Pattern.compile("资料对“([^”]{2,40})”").matcher(question);
        if (field.find()) return field.group(1);
        var subject = TOPIC.matcher(question);
        if (subject.find()) return subject.group(1);
        String identifier = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (identifier.contains("region")) return "地区";
        if (identifier.contains("tool")) return "工具";
        if (identifier.contains("auth") || identifier.contains("login")) return "认证方式";
        return "本次选择";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    /** 子维度仍需独立答案，例如已选择分析工具不代表工具版本已经明确。 */
    private boolean matchesTopic(String finding, String topic) {
        if (topic.equals("研究地区") || topic.equals("地区范围")) return finding.contains("地区");
        if (topic.equals("分析工具")) return finding.contains("工具");
        return finding.contains(topic);
    }

    private List<String> conflictValues(String question) {
        return Pattern.compile("（([^）]{1,100})）").matcher(question).results()
                .map(match -> normalizeValue(match.group(1))).distinct().toList();
    }

    /** 数字和金额边界必须完整，例如 500 不能命中 50，十五万元不能命中五万元。 */
    private boolean containsCompleteValue(String text, String value) {
        return Pattern.compile("(?<![A-Za-z0-9零一二三四五六七八九十百千万亿点.])" + Pattern.quote(value)
                + "(?![A-Za-z0-9零一二三四五六七八九十百千万亿点.])").matcher(text).find();
    }

    private String normalizeValue(String value) {
        return value.trim().replaceFirst("[。.!！]$", "").toLowerCase(Locale.ROOT);
    }
}
