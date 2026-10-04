package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 将当前明确的授权状态与未来申请、新共享对象区分，未获批准是已知限制而不是待选答案。
 * 只接受原始需求和调用方筛过用途的资料；相互矛盾、尚未知或复合授权题继续保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanningAuthorizationState {
    private static final String SCOPE = "跨(?:院|机构|部门|工作区|租户)";
    private static final String MATERIAL = "(?:原始记录|原始数据|患者原始记录)";
    private static final String ACTION = SCOPE + "(?:(?:共享)" + MATERIAL + "|" + MATERIAL + "共享)";
    private static final Pattern ASSERTION = Pattern.compile("(" + ACTION + ")[：:]?(尚未获批准|尚未批准|未获批准|未批准|已获批准|已批准)");
    private static final Pattern QUESTION = Pattern.compile("^(?:本次(?:整理)?方案中[，,]|本次[，,]|目前[，,]|当前[，,])?"
            + "(" + ACTION + ")(?:是否)(?:已经|已)?(?:获得批准|获批准|批准|获得授权|获授权|授权)[？?]?$" );
    private record Status(String action, boolean granted) { }
    private final List<Status> statuses;
    private final boolean separateHospitalStatistics;
    private final boolean explicitDeliveryChoice;

    private PlanningAuthorizationState(List<Status> statuses, boolean separateHospitalStatistics, boolean explicitDeliveryChoice) {
        this.statuses = List.copyOf(statuses);
        this.separateHospitalStatistics = separateHospitalStatistics;
        this.explicitDeliveryChoice = explicitDeliveryChoice;
    }

    /** 逐个完整语句收集现状；条件句、将来申请或示例不是当前授权事实。 */
    static PlanningAuthorizationState from(List<String> evidence) {
        List<Status> result = new ArrayList<>();
        for (String source : evidence) {
            if (source == null) continue;
            for (String clause : source.split("[。；;\\r\\n]+")) {
                String text = clause.replaceAll("[\\s`*]", "");
                if (text.matches(".*(?:若|如果|假如|示例|例如|假设|未来|将来|新增授权|申请授权).*")) continue;
                var match = ASSERTION.matcher(text);
                while (match.find()) {
                    // 具名机构的局部授权不能升级成全部跨院共享状态；具名授权题继续按具体范围保留。
                    if (text.substring(0, match.start()).matches(".*(?:医院|机构|公司|部门|工作区|租户).*")) continue;
                    result.add(new Status(action(match.group(1)), match.group(2).startsWith("已")));
                }
            }
        }
        boolean separate = evidence.stream().filter(java.util.Objects::nonNull)
                .map(source -> source.replaceAll("[\\s`*、，,]", ""))
                .anyMatch(source -> source.contains("各院分别处理") && source.contains("仅交付不可识别的统计结构"));
        boolean choice = evidence.stream().filter(java.util.Objects::nonNull)
                .anyMatch(source -> source.matches("(?s).*(?:让我选择|由我选择|询问我|由用户选择).{0,20}(?:交付|汇总).*"));
        return new PlanningAuthorizationState(result, separate, choice);
    }

    /**
     * 已核实的当前授权状态移入约束，而不再次作为用户待选项；全文保留。
     * 只接受完整状态或已给定的分院统计限制，新对象、期限、取值和实际授权问题均不能借此消除。
     */
    static boolean verifiedRuleReminder(String finding, List<String> evidence) {
        String text = finding.replaceAll("[\\s`*]", "");
        var assertion = ASSERTION.matcher(text);
        if (!assertion.find() || assertion.start() != 0) return false;
        var known = from(evidence);
        boolean granted = assertion.group(2).startsWith("已");
        if (!known.value(action(assertion.group(1))).equals(Optional.of(granted))) return false;
        String tail = text.substring(assertion.end());
        if (tail.matches("[。.!！]?")) return true;
        if (granted || !action(assertion.group(1)).equals("跨院共享原始记录") || !known.separateHospitalStatistics) return false;
        // 条件化的“未来取得授权再共享”是当前禁令的说明，不是当前授权状态重新待选。
        // 逐个完整分句核对，新增期限、范围或要求用户实际选定授权时仍整条保留。
        List<String> restrictions = java.util.Arrays.stream(tail.replaceFirst("^[，,]", "").split("[。；;]+"))
                .map(String::strip).filter(value -> !value.isEmpty()).toList();
        return !restrictions.isEmpty() && restrictions.stream().allMatch(clause ->
                clause.matches("当前只能各院分别处理并交付不可识别的统计结构")
                        || clause.matches("当前仅交付各院分别处理的不可识别统计结构")
                        || clause.matches("当前各院分别处理[、，,]仅交付不可识别的统计结构")
                        || clause.matches("若后续授权状态变化[，,]将影响整理步骤与交付范围")
                        || clause.matches("若后续需要(?:跨院)?(?:合并|共享)原始记录[，,](?:须|需|必须)先(?:取得|获得)授权(?:确认)?"));
    }

    /** 仅过滤同一当前状态题，不用已知禁止共享替代新对象、范围或申请流程的决定。 */
    boolean resolves(PlanQuestion question) {
        if (resolvesSeparateStatisticsDelivery(question)) return true;
        var match = QUESTION.matcher(question.question().replaceAll("\\s+", ""));
        if (!match.matches()) return false;
        String details = question.hint() + " " + String.join(" ", question.examples()) + " "
                + question.options().stream().map(option -> option.answer() + " " + option.description())
                .collect(java.util.stream.Collectors.joining(" "));
        // 题干一样但提示里增加新授权范围时不能删除整题；普通“批准/未批准”候选不属于新范围。
        if (details.matches("(?s).*(?:新增|未来|将来|申请|续期|有效期|授权期限|哪些字段|汇总统计|第三家).*")) return false;
        String completeText = question.question() + " " + details;
        if (Pattern.compile("[\\p{IsHan}]{1,16}(?:医院|机构|公司|部门)(?:的)?(?:原始记录|原始数据)(?:共享|授权)")
                .matcher(completeText).find()) return false;
        return value(action(match.group(1))).isPresent();
    }

    /** 继承已经明确的分院不可识别汇总交付，不把未获批准再变成选项；新增范围仍由用户确认。 */
    private boolean resolvesSeparateStatisticsDelivery(PlanQuestion question) {
        if (explicitDeliveryChoice || !separateHospitalStatistics
                || !value("跨院共享原始记录").equals(Optional.of(false))
                || !question.question().matches("^在跨院原始记录共享尚未获批准的情况下[，,]本次交付的汇总结果应如何处理[？?]$")) return false;
        String hint = question.hint().strip();
        if (!hint.isEmpty() && !hint.equals("跨院共享原始记录尚未获批准，不能把技术上可合并理解为已获授权；这决定最终交付物是各院分别处理还是仅交付不可识别的统计结构。")) return false;
        if (!question.examples().isEmpty() || question.options().isEmpty()) return false;
        return question.options().stream().allMatch(option -> {
            // 描述和推荐依据也能隐藏新增决定，不能只拿答案文本认定整题已解决。
            if (!List.of("不合并原始记录。", "不共享原始记录。",
                    "甲、乙医院分别整理，不合并原始记录，仅各自输出检查结果。",
                    "不共享原始记录，仅交付各院分别处理后的不可识别统计结构。").contains(option.description())) return false;
            if (!option.recommendationReason().isBlank() && !option.recommendationReason()
                    .equals("原始需求明确包含：各院分别处理。请核对适用范围后选择。")) return false;
            if (!List.of("各院分别处理", "不可识别统计结构", "仅交付不可识别统计结构").contains(option.label())) return false;
            String answer = option.answer().replaceAll("[\\s，,。]", "");
            return answer.equals("各院分别处理不合并原始记录仅各自输出检查结果")
                    || answer.equals("不共享原始记录仅交付各院分别处理后的不可识别统计结构");
        });
    }

    /** 已知限制供模型继承；冲突状态不自选一个值，也不把用户资料当作平台授权。 */
    List<String> knownRestrictions() {
        return statuses.stream().map(Status::action).distinct().filter(key -> value(key).isPresent())
                .map(key -> key + "：" + (value(key).orElseThrow() ? "资料明确已获批准；适用范围仍以原材料为准。"
                        : "当前尚未获批准，不得将技术可合并或普通用户确认当作已授权；本次不重问当前批准状态。"))
                .toList();
    }

    private Optional<Boolean> value(String key) {
        List<Boolean> values = statuses.stream().filter(status -> status.action().equals(key)).map(Status::granted).distinct().toList();
        return values.size() == 1 ? Optional.of(values.getFirst()) : Optional.empty();
    }

    /** 仅规范化共享动词的前后语序，记录、患者原始记录及汇总统计的对象保持不同。 */
    private static String action(String text) {
        var scope = Pattern.compile("^" + SCOPE).matcher(text);
        if (!scope.find()) return text;
        String prefix = scope.group();
        return text.startsWith(prefix + "共享") ? text : prefix + "共享" + text.substring(prefix.length(), text.length() - 2);
    }
}
