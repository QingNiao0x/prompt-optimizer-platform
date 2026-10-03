package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 保留用户明确规则，并拦截可确定的否定、数值边界和操作反转。
 * 仅比较作用对象与条件一致的短句，不把词汇相似当成开放域语义等价。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class RequirementFidelityGuard {
    private static final Pattern REQUIREMENT = Pattern.compile(
            "(?i)(必须|须|不得|禁止|不能|不允许|不超过|不少于|不低于|不高于|至少|最多|仅|只|"
                    + "保持|保留|排除|确认|取消|不填|不覆盖|不修改|不改变|不阻断|不回滚|"
                    + "研究范围[：:]|时间范围[：:]|统计口径[：:]|格式要求[：:]|"
                    + "\\bmust\\b|\\bshall\\b|\\bonly\\b|\\bnever\\b|\\bdo not\\b)");
    private static final Pattern INSTRUCTION_OVERRIDE = Pattern.compile(
            "(?i)(忽略|覆盖|绕过|删除|削弱).{0,16}(系统指令|平台约束|权限红线|安全规则)|"
                    + "ignore.{0,20}(system|platform).{0,12}(instruction|constraint)");
    private static final Pattern UNCERTAIN = Pattern.compile("[？?]|^(?:请问)?是否|例如|举例|假设");
    private static final Pattern ACTION = Pattern.compile("填充|覆盖|删除|部署|迁移|提交|发送|导出|执行");
    private static final Pattern NEGATION = Pattern.compile("不得|禁止|不允许|不需要|不能|不应|严禁|不必|无需|不要|不(?=填|覆盖|修改|改变|回滚|阻断|保持|保留|输出)");
    private static final Pattern QUANTITY = Pattern.compile(
            "\\d+(?:\\.\\d+)?|[零〇一二三四五六七八九十百千万两]+(?=个|条|次|字|页|元|天|年|月|小时|分钟|秒|%)");
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();

    /** 原始要求和明确执行决定可保留原句；未决回答与现状说明不能冒充本次执行规则。 */
    List<String> explicitRules(String rawPrompt, List<ConfirmedPlanDecision> decisions) {
        Set<String> answers = new LinkedHashSet<>();
        for (ConfirmedPlanDecision decision : decisions) {
            if (decision.scope() != Scope.UNRESOLVED && decision.scope() != Scope.CURRENT_STATE) {
                collect(decision.answer(), answers);
            }
        }
        Set<String> rules = new LinkedHashSet<>();
        collect(rawPrompt, rules);
        // 绑定确认可以改变业务选择；平台权限仍由独立的强制约束维护，不能在此被覆盖。
        Set<String> effective = new LinkedHashSet<>();
        for (String rule : rules) {
            if (!hasContradiction(rule, List.copyOf(answers))) {
                effective.add(rule);
                continue;
            }
            // 只移除被后续选择替代的完整子句；同句中其他保密、兼容性或异常处理要求仍须保留。
            String retained = clauses(rule).stream().filter(clause -> !hasContradiction(clause, List.copyOf(answers)))
                    .collect(java.util.stream.Collectors.joining("；"));
            if (!retained.isBlank()) effective.add(retained);
        }
        effective.addAll(answers);
        return List.copyOf(effective);
    }

    /** 模型不得以追加正确原文掩盖另一个执行段落中的相反要求。 */
    void validate(String draft, List<String> rules, String field) {
        for (String statement : clauses(draft)) {
            for (String rule : rules) {
                for (String expected : clauses(rule)) {
                    if (contradicts(expected, statement)) {
                        throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
                    }
                }
            }
        }
    }

    /** 文档与本次明确选择冲突时，不把文档升级为覆盖用户决定的校验规则。 */
    List<String> compatibleSourceRules(List<String> sources, List<String> explicitRules) {
        return sources.stream().filter(source -> !hasContradiction(source, explicitRules))
                .filter(source -> !hasContradiction(source, sources.stream().filter(other -> !other.equals(source)).toList()))
                .toList();
    }

    /** 只按完整规范化原句判定已覆盖，出现几个关键词不足以免除规则保留。 */
    boolean containsRule(String text, String rule) {
        if (text == null || text.isBlank() || rule == null || rule.isBlank()) return false;
        String expected = normalize(rule);
        // 子串可能处于否定、例子或历史引用中；无法证明完整覆盖时补回原句，不能仅凭包含关系放行。
        return Pattern.compile("(?<=[。！？!?])|\\R").splitAsStream(text)
                .map(this::normalize).anyMatch(expected::equals);
    }

    /** 提取完整明确要求，不把示例、未知回答或越权提示升级为执行规则。 */
    private void collect(String text, Set<String> rules) {
        if (text == null || text.isBlank()) return;
        // 保留同句中的条件与例外；“数据尚未提供，但不得编造”仍是明确要求，不能因未知词删掉整句。
        for (String sentence : text.split("(?<=[。！？!?])|\\R")) {
            String value = sentence.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
            if (!value.isBlank() && REQUIREMENT.matcher(value).find() && !UNCERTAIN.matcher(value).find()
                    && !INSTRUCTION_OVERRIDE.matcher(value).find() && !sensitiveValueDetector.containsCredential(value)) {
                rules.add(value);
            }
        }
    }

    /** 来源冲突与确认替代都需双向比较，不能只检测某一种否定方向。 */
    private boolean hasContradiction(String text, List<String> rules) {
        return clauses(text).stream().anyMatch(clause -> rules.stream().flatMap(rule -> clauses(rule).stream())
                .anyMatch(other -> contradicts(clause, other) || contradicts(other, clause)));
    }

    /** 只拒绝明确执行断言；未知、对照引用和用于验证拒绝行为的反例不作为新要求。 */
    private boolean contradicts(String expectedText, String actualText) {
        String expected = normalize(expectedText);
        String actual = normalize(actualText);
        if (expected.isBlank() || actual.isBlank() || expected.equals(actual)
                || actual.matches(".*(?:反例|错误示例|原错误|旧规则|历史规则|待确认|尚未|暂不确定|[？?]).*")) return false;
        if (actual.matches(".*(?:测试|验证).*(?:拒绝|拦截|失败|不应).*")
                || actual.matches(".*(?:拒绝|拦截|失败).*(?:测试|验证).*")) return false;

        // 表单回放中的语义反转不是简单漏词；同时识别保留有效值和取消不改变字段的要求。
        if (expected.matches(".*取消.{0,12}(?:保持|保留|不修改|不改变).*原值.*")
                && actual.contains("原值") && (actual.matches(".*(?:移除|删除)(?:原有|现有|已有)?(?:保持|保留)原值.*")
                || actual.matches(".*取消(?:保持|保留)原值(?:的)?(?:逻辑|保护|选项).*")
                || actual.matches(".*取消.*(?:清空|覆盖原值|无需保持|不再保持|不必保留).*"))
                && !actual.matches(".*(?:不得|不能|禁止|不要|不应)(?:移除|删除|取消).*保持原值.*")
                && !actual.matches(".*取消.*(?:不清空|不得清空|不覆盖原值|不得覆盖原值).*")) return true;
        if (expected.matches(".*(?:只填|仅填|仅向|只向|只给|仅给).*(?:null|空字符串|空字段|空值).*")
                && actual.matches(".*(?:清空.{0,12}字段|填入null|赋值为null|填入空字符串).*")
                && !actual.matches(".*(?:不|不得|禁止|不要|不能)(?:清空|填入|赋值).*")) return true;
        if (expected.matches(".*(?:保留|保持).*0.*false.*")
                && actual.matches(".*(?:将|把)?0.*false.*(?:视为空值|当作空值|覆盖|清空).*")
                && !actual.matches(".*(?:不得|禁止|不能|不要|不应).*")) return true;
        if (expected.matches(".*(?:确认后|经.{0,8}确认|先.{0,8}确认|提示用户是否|人工确认).*")
                && actual.matches(".*(?:无需|不需要|不必|跳过|绕过).{0,8}确认.*")
                && !actual.matches(".*(?:不得|禁止|不能|不要).*(?:跳过|绕过).*")
                && ACTION.matcher(expected).results().anyMatch(action -> actual.contains(action.group()))) return true;

        // 完整作用域相同才比较反向谓词，避免将另一个渠道、阶段或对象的合法规则判成冲突。
        var negative = NEGATION.matcher(expected);
        while (negative.find()) {
            for (String positive : List.of("", "允许", "可以", "必须", "应当", "需要")) {
                if ((expected.substring(0, negative.start()) + positive + expected.substring(negative.end())).equals(actual)) return true;
            }
        }
        var addedNegation = NEGATION.matcher(actual);
        while (addedNegation.find()) {
            String withoutNegation = actual.substring(0, addedNegation.start()) + actual.substring(addedNegation.end());
            if (withoutNegation.equals(expected)) return true;
        }
        if (expected.matches(".*(?:必须|需要|应当).*") && actual.matches(".*(?:无需|不必|不需要).*")) {
            if (expected.replaceFirst("必须|需要|应当", "").equals(actual.replaceFirst("无需|不必|不需要", ""))) return true;
        }
        QuantityRule left = quantityRule(expected);
        QuantityRule right = quantityRule(actual);
        return !left.values().isEmpty() && left.skeleton().equals(right.skeleton())
                && (!left.values().equals(right.values()) || !left.operators().equals(right.operators()));
    }

    /** 数量比较保持对象、动作和条件原样；只归一化数值及常见比较符，不推算材料未给出的值。 */
    private QuantityRule quantityRule(String text) {
        String comparable = text.replaceAll("不得超过|不超过|至多|最多|小于等于|<=", "≤")
                .replaceAll("不少于|不低于|至少|大于等于|>=", "≥")
                .replaceAll("严格大于|超过|大于", ">")
                .replaceAll("严格小于|少于|低于|小于", "<");
        List<String> values = QUANTITY.matcher(comparable).results().map(match -> normalizeNumber(match.group())).toList();
        List<String> operators = Pattern.compile("[≤≥<>]").matcher(comparable).results().map(match -> match.group()).toList();
        String skeleton = QUANTITY.matcher(comparable).replaceAll("#").replaceAll("[≤≥<>]", "@");
        return new QuantityRule(skeleton, values, operators);
    }

    /** 阿拉伯数值去除无意义小数；中文小整数与单位乘法仅用于比较已有原文。 */
    private String normalizeNumber(String text) {
        if (text.matches("\\d+(?:\\.\\d+)?")) return new BigDecimal(text).stripTrailingZeros().toPlainString();
        long total = 0;
        long section = 0;
        long digit = 0;
        String digits = "零一二三四五六七八九";
        for (char character : text.replace('〇', '零').replace('两', '二').toCharArray()) {
            int value = digits.indexOf(character);
            if (value >= 0) { digit = value; continue; }
            long unit = switch (character) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; case '万' -> 10000; default -> 1; };
            if (unit == 10000) { total += (section + digit) * unit; section = 0; }
            else section += (digit == 0 ? 1 : digit) * unit;
            digit = 0;
        }
        return Long.toString(total + section + digit);
    }

    /** 不在逗号处分割，避免把触发条件与操作拆开后扩大作用范围。 */
    private List<String> clauses(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Pattern.compile("[。；;\\r\\n]+").splitAsStream(text).filter(value -> !value.isBlank()).toList();
    }

    /** 仅消除排版差异；保留否定、数字、比较符和业务对象供后续判断。 */
    private String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceFirst("^(?:[-*#]+\\s*|\\d+[.)、]\\s+)", "")
                .replaceAll("[\\s`*‘’“”\"，,。；;]", "")
                .replaceFirst("^(?:约束|要求|规则|注意|空值处理)[:：]", "");
    }

    private record QuantityRule(String skeleton, List<String> values, List<String> operators) { }
}
