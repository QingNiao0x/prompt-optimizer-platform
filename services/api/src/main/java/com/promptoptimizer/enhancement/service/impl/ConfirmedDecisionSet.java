package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从服务端校验过的问题和回答构造本次决定。作用范围仅用于区分现状和目标，不推断项目事实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ConfirmedDecisionSet {
    private static final Pattern TOPIC = Pattern.compile("(研究地区|地区范围|数据来源|数据格式|输出格式|交付格式|交付内容|交付物|输出内容|输出方式|分析工具|分析方法|验收标准|统计口径|审批阈值|认证方式|登录方式|技术选型|技术栈|版本|范围|时限|规则|格式|地区|工具|口径|阈值)");
    private static final Pattern TARGET = Pattern.compile("(迁移|改为|替换|新增|目标|期望|希望|想要|计划采用|本次采用|最终采用)");
    private static final Pattern CURRENT = Pattern.compile("(现有|当前|目前|已实现|已经采用|正在使用)");
    private static final Pattern CHOICE_QUESTION = Pattern.compile("采用哪|选择哪|确认方式|应如何|应怎样|希望用|在什么时机|何时触发|是否还需要");
    private static final Pattern DETAIL = Pattern.compile("(版本|字段|异常|错误|单位|阈值|有效期|过期|格式|来源|刷新|退出|兼容)");

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
            Scope scope = PlanAnswerSemantics.unresolved(text) ? Scope.UNRESOLVED : confirmedScope(question, text);
            values.add(new ConfirmedPlanDecision(answer.questionId(), question, topic, scope, text));
        }
        return new ConfirmedDecisionSet(values);
    }

    List<ConfirmedPlanDecision> decisions() { return decisions; }

    /** 原始回答及其总体状态不变；下游核对已知事实时只使用可明确分开的已确认内容。 */
    List<ConfirmedPlanDecision> knownDecisions() {
        return decisions.stream().map(decision -> {
            if (decision.scope() != Scope.UNRESOLVED) return decision;
            String confirmed = PlanAnswerSemantics.confirmedPart(decision.answer());
            return new ConfirmedPlanDecision(decision.questionId(), decision.question(), decision.topic(),
                    confirmedScope(decision.question(), confirmed), confirmed);
        }).filter(decision -> !decision.answer().isBlank()).toList();
    }

    /** 一题可同时有已定选择和多个未决子项；提醒逐项带回原绑定 ID，不覆盖原始回答。 */
    List<ConfirmedPlanDecision> pendingDecisions() {
        return decisions.stream().filter(decision -> decision.scope() == Scope.UNRESOLVED)
                .flatMap(decision -> PlanAnswerSemantics.pendingParts(decision.answer()).stream().map(part -> {
                    boolean named = PlanAnswerSemantics.namesPendingSubject(part);
                    boolean explanationOnly = PlanAnswerSemantics.confirmedPart(decision.answer()).isBlank()
                            && PlanAnswerSemantics.pendingParts(decision.answer()).size() == 1;
                    // 裸待定后的单项核实解释仍继承原绑定题，避免新增核实识别改变旧问题的身份与范围。
                    if (explanationOnly && decision.answer().matches("(?s)^(?:暂不确定|尚未确定|不知道|不清楚)[。.!！].*")
                            && part.matches("(?s).*(?:尚未|仍未|未)核实.*")) named = false;
                    return new ConfirmedPlanDecision(decision.questionId(), named ? part : decision.question(),
                            named ? topic(part, decision.questionId()) : decision.topic(), Scope.UNRESOLVED,
                            named && !explanationOnly ? part : decision.answer());
                })).toList();
    }

    /** 仅供提醒核对的已定与未决视图，不新增外部字段，也不允许客户端指定内部状态。 */
    List<ConfirmedPlanDecision> assessedDecisions() {
        return java.util.stream.Stream.concat(knownDecisions().stream(), pendingDecisions().stream()).toList();
    }

    private static Scope confirmedScope(String question, String answer) {
        return TARGET.matcher(question + answer).find() ? Scope.TARGET
                : CURRENT.matcher(question).find() && !CHOICE_QUESTION.matcher(question).find() ? Scope.CURRENT_STATE : Scope.CHOICE;
    }

    /** 需求保持主查询，已确认的选项单独加入；不混入问题中的未选候选项。 */
    String retrievalQuery(String rawPrompt) {
        StringBuilder query = new StringBuilder(rawPrompt == null ? "" : rawPrompt.trim());
        for (ConfirmedPlanDecision decision : decisions) {
            String confirmed = decision.scope() == Scope.UNRESOLVED
                    ? PlanAnswerSemantics.confirmedPart(decision.answer()) : decision.answer();
            if (!confirmed.isBlank()) query.append('\n').append(decision.topic()).append('：').append(confirmed);
        }
        return query.toString();
    }

    /** 只有同主题的明确答案才能覆盖“未知”问题；资料冲突交由冲突检测单独判定。 */
    boolean coversUnknown(String finding) {
        if (finding == null || !finding.matches(".*(未知|未明确|未提供|尚未确定|未给出|未指定).*")) return false;
        if (finding.matches(".*(历史|例外|冲突|不一致|迁移后|新增范围).*")) return false;
        return knownDecisions().stream().anyMatch(decision ->
                PlanDecisionIdentity.repeatsReminder(finding, decision.question())
                && (!CURRENT.matcher(finding).find() || decision.scope() == Scope.CURRENT_STATE)
                && DETAIL.matcher(finding).results().allMatch(detail -> decision.question().contains(detail.group())));
    }

    /** 冲突题的已选择值必须属于本次已知的两份材料；第三个新取值不能被旧回答掩盖。 */
    boolean resolvesConflict(String field, List<String> values) {
        return decisions.stream().anyMatch(decision -> decision.questionId() != null
                && decision.questionId().startsWith("context-conflict-")
                && decision.topic().equals(field)
                && PlanningConflictIdentity.parse(decision.question()).filter(identity -> identity.hasValues(field, values)).isPresent()
                && (selectedConflictValue(decision).filter(selected -> values.stream()
                        .anyMatch(value -> normalizeValue(value).equals(selected))).isPresent()
                    || decision.answer().matches("^(?:两份|同时).*(?:保留|写出|标明).*")));
    }

    /** 用成对来源约束旧确认的覆盖范围；另一份新文件或新取值不得由旧问题自动解决。 */
    boolean resolvesConflict(String field, String firstPath, String firstValue, String secondPath, String secondValue) {
        return decisions.stream().anyMatch(decision -> PlanningConflictIdentity.parse(decision.question())
                .filter(identity -> identity.containsPair(field, firstPath, firstValue, secondPath, secondValue))
                .filter(identity -> decision.questionId() != null && decision.questionId().startsWith("context-conflict-")
                        && (identity.selectedValue(PlanAnswerSemantics.confirmedPart(decision.answer())).isPresent()
                        || decision.answer().matches("^(?:两份|同时).*(?:保留|写出|标明).*"))).isPresent());
    }

    /** 已绑定冲突题中的明确选择优先与新增证据比较，不能先拿被放弃的旧值构造新冲突。 */
    Optional<String> selectedConflictValue(String field) {
        return decisions.stream().filter(decision -> decision.topic().equals(field))
                .map(this::selectedConflictValue).flatMap(Optional::stream).findFirst();
    }

    /** 同一机构多项规则分别排序，仅选择实际属于当前对象取值集合的已确认值。 */
    Optional<String> selectedConflictValue(String field, List<String> values) {
        List<String> candidates = values.stream().map(PlanningConflictIdentity::canonical).toList();
        return decisions.stream().filter(decision -> decision.topic().equals(field))
                .map(this::selectedConflictValue).flatMap(Optional::stream).filter(candidates::contains).findFirst();
    }

    /** 仅在服务端问题给定的候选取值中解析明确选择；否定、条件及多个取值均不猜测。 */
    private Optional<String> selectedConflictValue(ConfirmedPlanDecision decision) {
        if (decision.questionId() == null
                || !decision.questionId().startsWith("context-conflict-")) return Optional.empty();
        var bound = PlanningConflictIdentity.parse(decision.question());
        if (bound.isPresent()) {
            return bound.get().selectedValue(PlanAnswerSemantics.confirmedPart(decision.answer()));
        }
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

    /** 已知主题取短标签；未知主题不用于自动消除歧义，也不猜测分类。 */
    private static String topic(String question, String id) {
        var field = Pattern.compile("资料对“([^”]{2,40})”").matcher(question);
        if (field.find()) return field.group(1);
        // “当前地区”可能只是确认交互的触发条件，不能把确认方式归类为地区现状。
        if (question.contains("确认方式")) return "确认方式";
        if (question.matches(".*(?:在什么时机触发|何时触发).*")) return "触发时机";
        if (question.matches(".*(?:没有记录|无记录|无匹配).*(?:提示|提醒).*")) return "无匹配提示";
        var subject = TOPIC.matcher(question);
        if (subject.find()) return subject.group(1);
        String identifier = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (identifier.contains("region")) return "地区";
        if (identifier.contains("tool")) return "工具";
        if (identifier.contains("auth") || identifier.contains("login")) return "认证方式";
        return "本次选择";
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
        return PlanningConflictIdentity.canonical(value);
    }
}
