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

    private EvidenceStateGuard() { }

    /**
     * 有效 Plan 的明确参数选值可更新同名旧待定谓词；不改原始记录，不解除其他参数或条件。
     * 当前情况、假设、条件式回答不作为本次选择，部分回答只用其明确片段。
     */
    static String reconcileConfirmedParameters(String text, List<ConfirmedPlanDecision> decisions) {
        if (text == null || text.isBlank()) return text;
        String updated = text;
        for (ConfirmedPlanDecision decision : decisions) {
            if (decision.scope() != Scope.CHOICE && decision.scope() != Scope.UNRESOLVED) continue;
            String answer = decision.scope() == Scope.UNRESOLVED
                    ? PlanAnswerSemantics.confirmedPart(decision.answer()) : decision.answer();
            for (String clause : answer.split("[。；;\\r\\n]+")) {
                String selected = clause.strip().replaceAll("[`*]", "");
                if (selected.matches(".*(?:如果|若|仅当|仅在|只针对|适用于|前提|尚未|未知|待定|待确认|可能|还是|或者).*")) continue;
                var explicit = EXPLICIT_PARAMETER_CHOICE.matcher(selected);
                if (!explicit.matches()) continue;
                String field = explicit.group(1).strip();
                // 完整具名属性必须同时存在于服务端原题；不能凭另一个对象的答案消除未知。
                if (!decision.question().contains(field)) continue;
                String subject = "(?<![\\p{L}\\d])" + Pattern.quote(field);
                updated = Pattern.compile(subject + "(?:目前)?(?:尚未确定|未确定|尚未决定|未决定|未知)")
                        .matcher(updated).replaceAll(java.util.regex.Matcher.quoteReplacement(
                                field + "已由用户确认选定，以本次确认答案为准"));
            }
        }
        return updated;
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
