package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.domain.TaskIntent;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 让有效绑定答案更新确认前的版本状态，仅替换可证明过时的状态谓词，保留同句独立限制。
 * 不改变原始输入、回答和证据卡片；新来源、新取值、生效授权及未来条件继续原样交付。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ResolvedPlanState {
    private static final Pattern GENERIC_VERSION = Pattern.compile("(?:当前|目前|本次)?(?:尚未|未)(?:确认|选定|决定)(?:采用|选择)(?:哪一?版|哪个版本)");
    private static final Pattern CONTINUE_RULE = Pattern.compile("该规则是否在本次继续采用(?:尚待|仍待|需待)负责人确认");
    private static final Pattern BINDING = Pattern.compile("资料对“([^”]+)”存在不同取值：(.+?)（([^）]+)）与 (.+?)（([^）]+)）");
    private static final Pattern MONEY = Pattern.compile("(?<![<>])(?:>=|<=|>|<|=)\\d+(?:\\.\\d+)?(?:元)?");
    private static final Pattern DELIVERY_PENDING = Pattern.compile(
            "(?<![\\p{L}\\d])(?:本次|具体)?交付(?:形式|内容|目标)(?:请|需要|需)(?:先|首先)(?:向我|向用户)?确认");
    private record Choice(ConfirmedPlanDecision decision, String field, String leftPath, String leftValue,
                          String rightPath, String rightValue, String selected) { }
    private final List<Choice> choices;
    private final String original;
    private final boolean deliveryConfirmed;
    private final List<ConfirmedPlanDecision> confirmedParameters;

    private ResolvedPlanState(List<Choice> choices, String original, boolean deliveryConfirmed,
                              List<ConfirmedPlanDecision> confirmedParameters) {
        this.choices = List.copyOf(choices);
        this.original = original == null ? "" : original;
        this.deliveryConfirmed = deliveryConfirmed;
        this.confirmedParameters = List.copyOf(confirmedParameters);
    }

    /** 仅由有效冲突题的唯一明确选值建立替代状态；总体未决但已明确版本的部分回答也可更新。 */
    static ResolvedPlanState from(ConfirmedDecisionSet decisions, String original) {
        List<Choice> choices = decisions.decisions().stream().filter(decision -> decision.questionId() != null
                        && decision.questionId().startsWith("context-conflict-"))
                .flatMap(decision -> {
                    var bound = BINDING.matcher(decision.question());
                    Optional<String> selected = PlanningConflictIdentity.parse(decision.question())
                            .flatMap(identity -> identity.selectedValue(PlanAnswerSemantics.confirmedPart(decision.answer())));
                    if (!bound.find() || selected.isEmpty()) return java.util.stream.Stream.empty();
                    return java.util.stream.Stream.of(new Choice(decision, bound.group(1), bound.group(2), bound.group(3),
                            bound.group(4), bound.group(5), selected.get()));
                }).toList();
        var rawIntent = TaskIntentResolver.resolve(TemplateCode.AUTO, original);
        var confirmedIntent = TaskIntentResolver.resolve(TemplateCode.AUTO, original, decisions.knownDecisions());
        // 仅更新原来未知的本次整体交付；子表格式、下一阶段目标和未决答案不能借此获得确认状态。
        boolean deliveryConfirmed = rawIntent.deliveryProfile() == TaskDeliveryProfile.GENERAL
                && confirmedIntent.status() == TaskIntent.ResolutionStatus.USER_CONFIRMED
                && decisions.knownDecisions().stream().anyMatch(decision ->
                        TaskIntentResolver.overallDeliveryQuestion(decision.question()));
        return new ResolvedPlanState(choices, original, deliveryConfirmed, decisions.decisions());
    }

    /** 修正当前执行视图，不抹掉比较符、未签署/未生效或“不自行折中”等独立要求。 */
    String reconcile(String text) {
        if (text == null || text.isBlank()) return text;
        String current = deliveryConfirmed ? DELIVERY_PENDING.matcher(text)
                .replaceAll("本次交付已在Plan阶段确认，以已确认决定为准") : text;
        current = EvidenceStateGuard.reconcileConfirmedParameters(current,
                confirmedParameters);
        current = EvidenceStateGuard.reconcileWindowChoiceRequests(current, original, confirmedParameters);
        if (choices.isEmpty()) return current;
        return current.lines().map(line -> {
            if (!GENERIC_VERSION.matcher(line).find() && !CONTINUE_RULE.matcher(line).find()) return line;
            StringBuilder result = new StringBuilder();
            var sentences = Pattern.compile("[^。]+[。]?").matcher(line);
            while (sentences.find()) {
                String sentence = sentences.group();
                String currentContext = line.substring(0, sentences.end());
                if (!GENERIC_VERSION.matcher(sentence).find() && !CONTINUE_RULE.matcher(sentence).find()) {
                    result.append(sentence);
                    continue;
                }
                List<Choice> matching = choices.stream().filter(choice -> matchesContext(sentence, currentContext, choice)).toList();
                if (matching.size() != 1) result.append(sentence);
                else result.append(CONTINUE_RULE.matcher(GENERIC_VERSION.matcher(sentence)
                                .replaceAll("本次版本已按用户确认选定"))
                        .replaceAll("本次版本选择已由用户确认，以已确认决定为准；不代表该材料已签署或已取得执行授权"));
            }
            return result.toString();
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * 无主语的旧状态只能依附唯一绑定版本和原文同段的双方边界。
     * 具名正文必须引用该规则或来源；新金额、另一来源和未来条件不能借用旧选择。
     */
    private boolean matchesContext(String line, String contextLine, Choice choice) {
        String normalized = canonical(contextLine);
        if (normalized.matches(".*(?:未来|后续|新增|生效日期|生效时间|有效期|仅针对|仅适用|退款|紧急).*")) return false;
        List<String> boundaries = MONEY.matcher(canonical(choice.leftValue() + " " + choice.rightValue())).results()
                .map(match -> match.group().replace("元", "")).distinct().toList();
        if (MONEY.matcher(normalized).results().map(match -> match.group().replace("元", ""))
                .anyMatch(value -> !boundaries.contains(value))) return false;
        List<String> paths = Pattern.compile("[a-zA-Z0-9_./\\p{IsHan}-]+\\.(?:md|txt|docx|pdf)").matcher(line).results()
                .map(match -> match.group()).toList();
        if (paths.stream().anyMatch(path -> !path.equals(choice.leftPath()) && !path.equals(choice.rightPath()))) return false;
        // 明确当前规则来源或完整取值；“新文件引用旧文件”不是唯一旧版本，必须保留新证据。
        if ((normalized.contains(canonical(choice.leftValue())) || normalized.contains(canonical(choice.rightValue())))
                && (normalized.contains(canonical(choice.field())) || !paths.isEmpty())) return true;
        String reduced = GENERIC_VERSION.matcher(line).replaceAll("").replaceAll("[\\s。；;，,：:\\-*]", "");
        if (!reduced.matches("(?:不自行折中|不得自行折中|不能自行折中)?") || choices.size() != 1) return false;
        return original.lines().anyMatch(paragraph -> {
            String context = canonical(paragraph);
            return GENERIC_VERSION.matcher(paragraph).find() && context.contains(canonical(choice.field()))
                    && !boundaries.isEmpty() && boundaries.stream().allMatch(value -> MONEY.matcher(context).results()
                    .map(match -> match.group().replace("元", "")).anyMatch(value::equals));
        }) || original.lines().anyMatch(paragraph -> {
            String context = canonical(paragraph);
            // 原文可在上一行声明机构，本段仍需明确审批属性和双方边界；不从相同数字猜测另一业务对象。
            return choices.size() == 1 && canonical(original).contains(canonical(choice.field()))
                    && GENERIC_VERSION.matcher(paragraph).find() && context.contains("审批")
                    && canonical(choice.leftValue()).contains("审批") && canonical(choice.rightValue()).contains("审批")
                    && !boundaries.isEmpty() && boundaries.stream().allMatch(value -> MONEY.matcher(context).results()
                    .map(match -> match.group().replace("元", "")).anyMatch(value::equals));
        });
    }

    private static String canonical(String text) {
        return PlanningConflictIdentity.canonical(text).replaceAll("[\\s`*]", "")
                .replace("才进入", "时").replace("进入", "");
    }
}
