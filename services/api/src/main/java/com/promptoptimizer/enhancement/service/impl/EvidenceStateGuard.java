package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校验明确声明的未知状态，不把“资料未说明”变成不存在或已选定的专业参数。
 * 仅匹配完整具名对象，不推测开放域事实；条件、合法否定和另一对象不据此拒绝。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class EvidenceStateGuard {
    private static final Pattern PREFIX_UNKNOWN = Pattern.compile(
            "(?:资料|材料|附件|上下文)(?:尚)?(?:未说明|未提及|未提供|未证明|没有说明|不能证明)([^，,。；;\\r\\n]{2,60})");
    private static final Pattern SUFFIX_UNKNOWN = Pattern.compile(
            "^([^，,。；;\\r\\n]{2,60}?)(?:目前)?(?:尚未确定|暂不确定|尚未提供|尚未核实|未核实|未确定|未知)(?:[，,。；;]|$)");
    private static final Pattern PREFIX_ABSENCE = Pattern.compile(
            "^(?:当前|目前|本平台|本项目|该平台|该项目|平台|项目|系统)?(?:明确)?(?:没有|不支持|未实现|尚未实现|不存在)([^，,。；;]{2,60})$");
    private static final Pattern SUFFIX_ABSENCE = Pattern.compile(
            "^([^，,。；;]{2,60}?)(?:不存在|未实现|不受支持|不支持)$");
    private static final Pattern CONDITIONAL_OR_BOUNDARY = Pattern.compile(
            "^(?:若|如果|假如|当用户(?:确认|选定)|用户(?:确认|选定).*后|待用户(?:确认|选定))"
                    + "|(?:不得|不能|不应|不要|不代表|不等于|不可认定|不能认定).*(?:没有|不存在|采用|使用|设为)"
                    + "|未说明|未提供|未核实|未确定|尚未确定|暂不确定|待确认|待定|[？?]");
    private static final Pattern EXPLICIT_PARAMETER_CHOICE = Pattern.compile(
            "^(?:本次|最终)?([^，,。；;\\r\\n]{2,30}?)(?:明确)?(?:采用|使用|选定)([^，,。；;\\r\\n]+)$");
    private static final Pattern FACT_LIST_MARKER = Pattern.compile("其中明确[：:]?");
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(.*)$");
    private static final Pattern WINDOW_TASK_SCOPE = Pattern.compile("^(?:为|请为)((?:甲院|乙院)(?:19|20)\\d{2}年)(?:的)?门诊运营组");
    private static final Pattern NAMED_HOSPITAL_YEAR = Pattern.compile("(?:甲院|乙院|丙院|丁院)(?:19|20)\\d{2}年");
    private static final Pattern WINDOW_CHOICE_REQUEST = Pattern.compile(
            "^本次是否继续沿用(\\d{1,4}(?:\\.\\d{1,4})?小时)(?:观察)?窗口[，,]"
                    + "还是提出(\\d{1,4}(?:\\.\\d{1,4})?小时)(?:观察)?(?:窗口)?候选供院方审批[，,]"
                    + "尚未选择[，,]请先让我确认[。.]?$");

    private EvidenceStateGuard() { }

    /**
     * 有效 Plan 的明确参数选值可更新同名旧待定谓词；不改原始记录，不解除其他参数或条件。
     * 当前情况、假设、条件式回答不作为本次选择，部分回答只用其明确片段。
     */
    static String reconcileConfirmedParameters(String text, List<ConfirmedPlanDecision> decisions) {
        if (text == null || text.isBlank()) return text;
        String updated = reconcileWindowChoiceRequests(reconcileChannelSelection(text, decisions), text, decisions);
        for (ConfirmedPlanDecision decision : decisions) {
            if (decision.scope() != Scope.CHOICE && decision.scope() != Scope.UNRESOLVED) continue;
            String answer = decision.scope() == Scope.UNRESOLVED
                    ? PlanAnswerSemantics.confirmedPart(decision.answer()) : decision.answer();
            for (String clause : answer.split("[。；;\\r\\n]+")) {
                String selected = clause.strip().replaceAll("[`*]", "");
                if (selected.matches(".*(?:如果|若|仅当|仅在|只针对|适用于|前提|尚未|未知|待定|待确认|可能|还是|或者).*")) continue;
                // 同句的“包括缺失记录”等说明不改变前面的实际选值，条件与未决仍按完整句先核对。
                var explicit = EXPLICIT_PARAMETER_CHOICE.matcher(selected.split("[，,]", 2)[0]);
                if (!explicit.matches()) continue;
                String field = explicit.group(1).strip();
                // 实际绑定回答明确选择完整参数名时，以回答的对象和属性为准，不依赖原题的改写词。
                // 未具名参数仍须与原题匹配；不得把通用“采用某口径”关联到另一指标或另一院。
                boolean namedParameter = field.matches("[^与和及、，,。；;]{2,60}(?:分母|阈值|观察窗口|覆盖度)");
                if (!namedParameter && !parameterName(decision.question()).contains(parameterName(field))) continue;
                updated = reconcileCurrentParameterState(updated, field);
            }
        }
        return updated;
    }

    /**
     * 仅更新真实反例的完整本次请求，任务首句须唯一标明机构年份，回答须选择同一已列窗口。
     * 引文、围栏及后续条件保留；旧请求的候选审批边界不升级为已批准，也不带走独立阈值。
     */
    static String reconcileWindowChoiceRequests(String text, String original, List<ConfirmedPlanDecision> decisions) {
        var scope = WINDOW_TASK_SCOPE.matcher(original == null ? "" : original.strip());
        if (text == null || text.isBlank() || !scope.find()) return text;
        String subject = scope.group(1);
        if (java.util.stream.Stream.of(original, text).anyMatch(value -> NAMED_HOSPITAL_YEAR.matcher(value)
                .results().map(match -> match.group()).anyMatch(named -> !named.equals(subject)))) return text;
        var selected = UnresolvedDecisionContract.selectedWindowValue(subject, decisions);
        if (selected.isEmpty()) return text;
        StringBuilder result = new StringBuilder(text.length());
        String activeFence = null;
        for (String line : text.split("(?<=\\n)", -1)) {
            var fence = FENCE.matcher(line.strip());
            if (fence.matches()) {
                if (activeFence == null) activeFence = fence.group(1);
                else if (fence.group(1).charAt(0) == activeFence.charAt(0)
                        && fence.group(1).length() >= activeFence.length() && fence.group(2).isBlank()) activeFence = null;
                result.append(line);
                continue;
            }
            if (activeFence != null || line.stripLeading().startsWith(">")) {
                result.append(line);
                continue;
            }
            for (String sentence : line.split("(?<=[。])", -1)) {
                String prose = sentence.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
                var request = WINDOW_CHOICE_REQUEST.matcher(prose);
                if (!request.matches() || !request.group(1).equals(selected.get())) result.append(sentence);
                else result.append(sentence.replace(prose, "本次已确认采用" + subject + "的"
                        + selected.get() + "观察窗口；" + request.group(2) + "候选的审批要求保持不变。"));
            }
        }
        return result.toString();
    }

    /**
     * 只更新当前叙述，引用块与代码围栏逐字保留；保留换行方式，不将代码中的示例升级为执行决定。
     * 围栏结束标记必须同类且不短于开始标记，不能被围栏内的更短反引号提前结束保护。
     */
    private static String reconcileCurrentParameterState(String text, String field) {
        StringBuilder result = new StringBuilder(text.length());
        char fenceType = 0;
        int fenceLength = 0;
        for (String line : text.split("(?<=\\n)", -1)) {
            var fence = FENCE.matcher(line.strip());
            if (fence.matches()) {
                String marker = fence.group(1);
                if (fenceType == 0) {
                    fenceType = marker.charAt(0);
                    fenceLength = marker.length();
                } else if (marker.charAt(0) == fenceType && marker.length() >= fenceLength
                        && fence.group(2).isBlank()) {
                    fenceType = 0;
                }
                result.append(line);
                continue;
            }
            if (fenceType != 0 || line.stripLeading().startsWith(">")) {
                result.append(line);
                continue;
            }
            String current = reconcileCoordinatedState(line, field);
            current = reconcileEnumeratedState(current, field);
            result.append(reconcileStandaloneState(current, field));
        }
        return result.toString();
    }

    /**
     * 平铺摘要的顿号只是事实之间的分隔，不扩大为任意叙述的参数边界。
     * 仅接受无共享主体的“其中明确”列表；来源前缀具名机构或年份时保留未知，不能借用全局确认。
     */
    private static String reconcileEnumeratedState(String text, String field) {
        var marker = FACT_LIST_MARKER.matcher(text);
        if (!marker.find() || text.matches("(?s).*(?:如果|若|假如|假设|例如|示例|引用|[“”\\\"`]).*")) return text;
        String prefix = text.substring(0, marker.start());
        // 未知叙述前缀不推断为中性来源；路径中的机构和年份也不能解除其限定范围。
        if (!prefix.matches("\\s*(?:数据字典来源[：:][^；;\\r\\n]+[；;]\\s*)?")
                || prefix.matches("(?s).*(?:院|机构|中心|部门|团队|学校|公司|集团|[12][0-9]{3}年).*")) return text;
        String list = text.substring(marker.end());
        String current = java.util.Arrays.stream(list.split("、", -1))
                .map(item -> reconcileCoordinatedState(item, field))
                .collect(java.util.stream.Collectors.joining("、"));
        return text.substring(0, marker.end()) + current;
    }

    /** 同名独立未知只更新当前断言；未来条件、示例和引用不随本次参数选值改变。 */
    private static String reconcileStandaloneState(String text, String field) {
        var parameter = Pattern.compile("^(.+?)(?:的)?(分母|阈值|观察窗口|覆盖度)$").matcher(field);
        String named = parameter.matches() ? Pattern.quote(parameter.group(1)) + "(?:的)?" + Pattern.quote(parameter.group(2))
                : Pattern.quote(field);
        var state = Pattern.compile("(?<![\\p{L}\\d])" + named
                + "(?:目前)?(?:尚未确定|未确定|尚未决定|未决定|还未选定|尚未选定|未选定|未知)");
        return Pattern.compile("[^。；;\\r\\n]+[。；;]?").matcher(text).replaceAll(match -> {
            String sentence = match.group();
            if (sentence.matches("(?s).*(?:若|如果|假如|假设|仅当|仅在|例如|示例|引用|[“”\\\"`]).*")) {
                return java.util.regex.Matcher.quoteReplacement(sentence);
            }
            return java.util.regex.Matcher.quoteReplacement(state.matcher(sentence).replaceAll(
                    java.util.regex.Matcher.quoteReplacement(field + "已由用户确认选定，以本次确认答案为准")));
        });
    }

    /** 同一资料把参数并列标为未知时，只更新本次真正选择的完整参数，保留其余参数的未决状态。 */
    private static String reconcileCoordinatedState(String text, String field) {
        var declarations = Pattern.compile("(?:^|(?<=[。；;\\r\\n]))"
                + "([^，,。；;\\r\\n]{2,120}?)(?:尚需分别确定|尚未分别确定|均尚未确定)");
        return declarations.matcher(text).replaceAll(match -> {
            String named = match.group(1).strip();
            if (named.matches("^(?:若|如果|假设|假如|例如|示例|引用|>|```).*")) return java.util.regex.Matcher.quoteReplacement(match.group());
            var parameters = java.util.Arrays.stream(named.split("与|和|及|、")).map(String::strip).toList();
            if (parameters.size() < 2 || parameters.size() > 6
                    || parameters.stream().anyMatch(value -> !value.matches(".{2,60}(?:分母|阈值|观察窗口|覆盖度)"))
                    || parameters.stream().noneMatch(value -> parameterName(value).equals(parameterName(field)))) {
                return java.util.regex.Matcher.quoteReplacement(match.group());
            }
            return java.util.regex.Matcher.quoteReplacement(parameters.stream().map(value -> value
                    + (parameterName(value).equals(parameterName(field)) ? "已按本次回答确认" : "尚未确定"))
                    .collect(java.util.stream.Collectors.joining("；")));
        });
    }

    /** 只规范可选“的”和完整性指标的已知示例别名，机构、年份及限定范围逐字保留。 */
    private static String parameterName(String text) {
        return text.replaceAll("完整性指标[（(](?:如|例如)字段缺失率[）)](?=(?:的)?分母)", "完整性指标")
                .replaceAll("的(?=分母|阈值|观察窗口|覆盖度)", "");
    }

    /** 仅更新单一本次通知渠道的旧选择状态；绑定回答必须真选渠道，偏好、未来条件和另一对象不继承。 */
    private static String reconcileChannelSelection(String text, List<ConfirmedPlanDecision> decisions) {
        var candidates = decisions.stream().filter(decision -> decision.question().matches(
                "^本次[^。；;\\n]{0,32}通知[^。；;\\n]{0,24}渠道[？?]$")).toList();
        if (candidates.size() != 1) return text;
        String answer = candidates.getFirst().scope() == Scope.UNRESOLVED
                ? PlanAnswerSemantics.confirmedPart(candidates.getFirst().answer()) : candidates.getFirst().answer();
        boolean chosen = java.util.Arrays.stream(answer.split("[。；;\\r\\n]+"))
                .map(String::strip).anyMatch(clause -> clause.matches(
                        "^(?:本次)?(?:仅|只|同时)?(?:使用|启用|采用)(?:现有)?(?:站内信|邮件)(?:与(?:站内信|邮件))?(?:通知|渠道)?.*"));
        if (!chosen) return text;
        return text.lines().map(line -> {
            if (line.matches("(?s).*(?:如果|假如|假设|仅当|未来|以后|其他医院|另一院).*")) return line;
            return line.replaceAll("本次(?:尚未|还未|未)(?:作出)?(?:最终)?渠道选择", "本次通知渠道已按计划回答确认");
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 只处理证据明确标记的未知对象；不以“没有查到”宣称未知对象已经不存在。 */
    static void validate(String draft, List<String> rules, String field) {
        Set<String> unknownObjects = new LinkedHashSet<>();
        for (String rule : rules) {
            var prefix = PREFIX_UNKNOWN.matcher(rule);
            while (prefix.find()) unknownObjects.add(object(prefix.group(1)));
            for (String sentence : rule.split("[。；;\\r\\n]+")) {
                var suffix = SUFFIX_UNKNOWN.matcher(sentence.strip());
                if (suffix.find()) unknownObjects.add(object(suffix.group(1)));
            }
        }
        unknownObjects.removeIf(value -> value.length() < 2 || value.length() > 40);
        if (unknownObjects.isEmpty() || draft == null || draft.isBlank()) return;
        for (String sentence : draft.split("[。；;\\r\\n]+")) {
            String text = sentence.strip().replaceFirst("^(?:[-*#]+\\s*|\\d+[.)、]\\s*)", "");
            if (CONDITIONAL_OR_BOUNDARY.matcher(text).find()) continue;
            var prefix = PREFIX_ABSENCE.matcher(text);
            var suffix = SUFFIX_ABSENCE.matcher(text);
            String absent = prefix.matches() ? object(prefix.group(1)) : suffix.matches() ? object(suffix.group(1)) : "";
            if (unknownObjects.contains(absent)) throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            for (String unknown : unknownObjects) {
                // 必须出现完整对象再跟选定谓词，不能因同句另一个分析工具或相似术语误拒。
                String subject = Pattern.quote(unknown) + "(?:功能|能力|参数|的取值)?";
                if (Pattern.compile("^(?:当前|目前|本次)?" + subject
                        + "(?:明确)?(?:采用|使用|设为|默认采用|默认使用|暂按|为|是)(?!未知|待定|待确认).+")
                        .matcher(text.replaceAll("[\\s`*]", "")).matches()) {
                    throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
                }
            }
        }
    }

    /** 仅移除展示前缀及通用“功能”后缀，保留机构、业务对象、条件和专业参数名称。 */
    private static String object(String value) {
        return value.strip().replaceAll("[\\s`*“”\"]", "")
                .replaceFirst("^(?:当前|目前|本次|平台|项目|系统)", "")
                .replaceFirst("(?:功能|能力)(?:的实现)?$", "");
    }
}
