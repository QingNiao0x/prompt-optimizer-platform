package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 将明确未决的具名参数绑定到交付物，防止正文保留未知而表格或公式擅自确定。
 * 只检查可定位的对象与属性，不替用户选择专业口径，也不执行下游任务。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class UnresolvedDecisionContract {
    private static final Pattern DECLARATION = Pattern.compile(
            "([^。；;，,：:\\r\\n？?]{2,80}?)(?:尚未|仍未|暂未|未)(?:确定|决定|核实|明确)");
    private static final Pattern PARAMETER = Pattern.compile("^(.{2,65}?)(?:的)?(分母|阈值|观察窗口|覆盖度)$");
    private static final Pattern CONDITIONAL = Pattern.compile("若|如果|假如|假设|仅当|只有|例如|示例|引用|不要|不得|不能|不应|禁止");
    private static final Pattern PENDING = Pattern.compile("待确认|待定|未决|尚未|未确定|未决定|未核实|待核实|TBD|None|null", Pattern.CASE_INSENSITIVE);
    static final String DELIVERY_GUIDANCE = "同一未决决定在正文、表格、公式及伪代码中保持一致："
            + "相关参数格明确标为“待确认”，不得填入惯例、示例值或占位口径；"
            + "依赖该参数的计算只声明待确认参数并在确认前停止该计算，不能设置默认值或生成假结果。"
            + "已确认决定仅适用于对应对象、指标和条件，其余已具备条件的步骤继续完成。";

    private record Parameter(String subject, String property) { }
    private final List<Parameter> parameters;

    private UnresolvedDecisionContract(List<Parameter> parameters) {
        this.parameters = List.copyOf(parameters);
    }

    /** 原需求及有效回答分别建立未决状态；另一指标的已确认分母不能消除当前指标的未知。 */
    static UnresolvedDecisionContract from(String raw, ConfirmedDecisionSet decisions) {
        var sources = new ArrayList<>(declaredPending(raw));
        decisions.pendingDecisions().forEach(value -> sources.addAll(declaredPending(value.answer())));
        List<Parameter> parameters = sources.stream().flatMap(value -> DECLARATION.matcher(value).results())
                .map(match -> canonical(match.group(1))).map(PARAMETER::matcher).filter(java.util.regex.Matcher::matches)
                .map(match -> new Parameter(match.group(1).replaceFirst("的$", ""), match.group(2)))
                .filter(value -> !value.subject().matches(".*(?:与|和|及|是否|如何|哪些|其他|其余).*"))
                .distinct().toList();
        return new UnresolvedDecisionContract(parameters);
    }

    /** 只继承独立的明确未知陈述，不从关键词缺失、假设句、引文或数据代码制造问题。 */
    static List<String> declaredPending(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        var result = new ArrayList<String>();
        for (String sentence : raw.split("[。；;\\r\\n]+")) {
            String value = sentence.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
            if (CONDITIONAL.matcher(value).find() || value.startsWith(">") || value.startsWith("```")) continue;
            var declarations = DECLARATION.matcher(value);
            while (declarations.find()) {
                String subject = declarations.group(1).strip();
                if (subject.matches(".*(?:分母|阈值|观察窗口|覆盖度|插补|方法|审批标准|退款标准)$")) {
                    result.add(declarations.group().strip() + "。");
                }
            }
        }
        return result.stream().distinct().toList();
    }

    /** 模型自己的确定口径与明确未决状态冲突时进入既有修复预算，不靠附加未知尾注放行。 */
    void validate(String content, String field) {
        if (parameters.isEmpty() || content == null) return;
        List<String> header = List.of();
        for (String line : content.lines().toList()) {
            String text = line.strip();
            if (text.startsWith("|")) {
                List<String> cells = cells(text);
                if (cells.stream().anyMatch(value -> value.matches("分母|阈值|观察窗口|覆盖度"))) { header = cells; continue; }
                if (text.matches("[|:\\-\\s]+") || header.isEmpty() || cells.size() != header.size()) continue;
                for (Parameter parameter : parameters) {
                    int index = header.indexOf(parameter.property());
                    if (index < 0) continue;
                    boolean sameObject = java.util.stream.IntStream.range(0, cells.size())
                            .filter(column -> column != index).anyMatch(column -> sameSubject(cells.get(column), parameter.subject()));
                    if (sameObject && !unknownCell(cells.get(index))) reject(field);
                }
            } else {
                header = List.of();
                for (String clause : text.split("[。；;]+")) {
                    if (CONDITIONAL.matcher(clause).find() || PENDING.matcher(clause).find()) continue;
                    String compact = canonical(clause);
                    for (Parameter parameter : parameters) {
                        String target = Pattern.quote(parameter.subject()) + "(?:的)?" + Pattern.quote(parameter.property());
                        if (Pattern.compile(target + "(?:采用|取|为|是|=|设为|定义为)(?=.+)").matcher(compact).find()) reject(field);
                    }
                }
            }
        }
    }

    /** 具体口径不能通过追加“尚待确认”来伪装成空参数，未知格只允许状态和等待说明。 */
    private boolean unknownCell(String value) {
        String text = canonical(value);
        return PENDING.matcher(text).find() && !text.matches(".*(?:记录数|样本数|len\\(|count\\(|\\d+(?:分钟|小时|%)).*");
    }

    /** 完整具名对象逐字核对；有限的跨字段一致性子指标别名只覆盖该同类指标。 */
    private boolean sameSubject(String candidate, String subject) {
        String name = canonical(candidate).replaceAll("[*`\\s]", "");
        if (name.equals(subject)) return true;
        return subject.matches("(?:跨字段(?:逻辑)?)?一致性指标")
                && name.matches("(?:跨字段(?:逻辑)?)?一致性(?:指标|率)|出院日期早于入院日期不一致率");
    }

    private static List<String> cells(String row) {
        return Arrays.stream(row.substring(1, row.endsWith("|") ? row.length() - 1 : row.length()).split("\\|", -1))
                .map(String::strip).map(UnresolvedDecisionContract::canonical).toList();
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\s*`“”]", "");
    }

    private static void reject(String field) {
        throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
    }
}
