package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 核对具名条件式确认的证据状态，阻止模型把未来假设改成已经成立的用户确认。
 * 仅核对完整命题；另一对象、合法条件分支与服务端绑定的实际答案不受影响。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ConditionalConfirmationGuard {
    private static final Pattern CONDITIONAL_CONFIRMATION = Pattern.compile(
            "(?:^|[。；;\\r\\n：:])\\s*(?:若|如果|假如|假设)(?:以后|今后|将来|后续|未来)?"
                    + "(?:我|用户)?(?:只|仅)?(?:确认|确定)([^，,。；;\\r\\n]{4,160})");
    private static final Pattern CONFIRMED_PREFIX = Pattern.compile(
            "(?:用户|我)(?:已|已经)(?:明确)?(?:确认|确定)([^，,。；;\\r\\n]{4,160})");
    private static final Pattern CONFIRMED_SUFFIX = Pattern.compile(
            "([^，,。；;\\r\\n]{4,160}?)(?:已|已经)(?:由|经)?用户(?:确认|确定)");
    private static final Pattern CONDITIONAL_OR_QUOTED = Pattern.compile(
            "(?:若|如果|假如|假设|仅当|只有|待|尚未|未|不|不得|不能|不要|禁止|不应|并非|不能认定|不代表|示例|例如|引用|原文).*$");

    private final List<String> hypotheses;
    private final List<String> actual;

    private ConditionalConfirmationGuard(List<String> hypotheses, List<String> actual) {
        this.hypotheses = List.copyOf(hypotheses);
        this.actual = List.copyOf(actual);
    }

    /**
     * 每份结果只解析一次证据；上传材料不能自称为用户确认，原需求与绑定答案分别建立实际证据。
     * 条件式答案仍加入假设来源，不因它经过 Plan 提交便把条件的前提视为已成立。
     */
    static ConditionalConfirmationGuard prepare(String rawPrompt, List<String> evidence,
                                                List<ConfirmedPlanDecision> confirmed) {
        var sources = new java.util.ArrayList<>(evidence);
        confirmed.stream().map(ConfirmedPlanDecision::answer).forEach(sources::add);
        List<String> hypotheses = sources.stream().flatMap(value -> CONDITIONAL_CONFIRMATION.matcher(value).results())
                .map(match -> canonical(match.group(1))).distinct().toList();
        var actual = new java.util.ArrayList<>(confirmed.stream().flatMap(decision -> java.util.Arrays.stream(
                        decision.answer().split("[。；;\\r\\n]+")))
                .filter(value -> !value.strip().matches("^(?:若|如果|假如|假设|仅当|只有|待|尚未|未|不|建议|例如|示例).*"))
                .map(ConditionalConfirmationGuard::canonical).toList());
        // 用户原文可以明确更新假设的状态，但材料中的同样措辞不具有这项确认效力。
        if (rawPrompt != null) {
            for (String sentence : rawPrompt.split("[。；;\\r\\n]+")) {
                String source = sentence.strip().replaceFirst("^(?:[-*#]+\\s*|\\d+[.)、]\\s*)", "");
                var asserted = CONFIRMED_PREFIX.matcher(source);
                if (asserted.find() && asserted.start() == 0) actual.add(canonical(asserted.group(1)));
            }
        }
        return new ConditionalConfirmationGuard(hypotheses, actual);
    }

    /**
     * 段落与提醒使用同一校验；失败进入已有受控修复，不在正文中同时保留真、假两种断言。
     * 来源和确认范围由 prepare 固定，不能由模型正文覆盖或借用别的对象。
     */
    void validate(String draft, String field) {
        if (draft == null || draft.isBlank()) return;
        if (hypotheses.isEmpty()) return;
        for (String sentence : draft.split("[。；;\\r\\n]+")) {
            var prefix = CONFIRMED_PREFIX.matcher(sentence);
            while (prefix.find()) {
                // 核对断言前的局部语境，不能因另一分句的口径“未决定”便豁免此处的假确认。
                String lead = sentence.substring(0, prefix.start());
                String local = lead.substring(Math.max(lead.lastIndexOf('，'), lead.lastIndexOf(',')) + 1);
                if (!sentence.strip().matches("^(?:[-*#]+\\s*)?(?:若|如果|假如|假设|待).*")
                        && !CONDITIONAL_OR_QUOTED.matcher(local).find()) {
                    rejectUnsupported(prefix.group(1), hypotheses, actual, field);
                }
            }
            var suffix = CONFIRMED_SUFFIX.matcher(sentence);
            while (suffix.find()) {
                if (!CONDITIONAL_OR_QUOTED.matcher(suffix.group(1)).find()) {
                    rejectUnsupported(suffix.group(1), hypotheses, actual, field);
                }
            }
        }
    }

    /** 完整命题须来自同一实际答案；只提到了相同指标或问题，不足以建立确认事实。 */
    private static void rejectUnsupported(String claim, List<String> hypotheses, List<String> actual, String field) {
        String assertion = canonical(claim);
        for (String hypothesis : hypotheses) {
            if (assertion.contains(hypothesis) && actual.stream().noneMatch(value -> value.contains(hypothesis))) {
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
        }
    }

    /** 只统一排版与分母谓词的明确同义语法，不删除对象、取值、比较符或否定。 */
    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\s`*“”\"]", "")
                .replaceFirst("^(?:[-#]+|\\d+[.)、])", "")
                .replaceAll("分母(?:的取值)?(?:采用|包含|包括|为|是)", "分母:");
    }
}
