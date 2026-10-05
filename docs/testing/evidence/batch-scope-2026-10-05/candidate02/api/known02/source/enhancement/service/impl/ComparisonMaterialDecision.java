package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 区分只读差异对照中的资料冲突与执行任务中必须选择的生效规则。
 * 仅继承用户已明确的“完整保留双方、不择一”交付，不删除资料和最终未决执行前提。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ComparisonMaterialDecision {
    private static final Pattern CONFLICT = Pattern.compile("资料对“([^”]{2,40})”存在不同取值：(.+?)（([^）]+)）与 (.+?)（([^）]+)）");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern BOUNDARY = Pattern.compile("(?:>=|<=|>|<|=)\\d+(?:\\.\\d+)?");
    private static final Pattern INDEPENDENT = Pattern.compile(
            "新增|另外|此外|另需|附加|跨境|跨租户|跨地区|新对象|新属性|生效日期|生效时间|授权|权限|隐私"
                    + "|币种|税前|含税|税率|工作日|小数精度|金额单位|审批流程|审批人|批准人|责任人|付款方式|退款方式"
                    + "|数据来源|时区|适用人群|观察窗口|分母|资格|期限|轮次|统计方法");
    private final String raw;
    private final List<ComparedConflict> conflicts;
    private final boolean comparisonOnly;

    private ComparisonMaterialDecision(PlanningProviderRequest input) {
        raw = canonical(input.rawPrompt());
        comparisonOnly = raw.matches("(?s).*(?:差异表|规则对照表|规则对照|差异对照).*")
                && raw.matches("(?s).*(?:不执行|不得执行|只读).*")
                && raw.matches("(?s).*(?:不能|不得|不要|不擅自).{0,20}(?:选择胜者|择一|采用其中|选定其中).*")
                && raw.matches("(?s).*(?:分别|同时|并列|保留|双方).{0,12}(?:完整条件|完整规则|完整取值|两个取值|两份规则).*")
                && raw.contains("来源")
                && !raw.matches("(?s).*(?:本次必须|本次需要|请先|还需|并需)(?:选定|选择|确定).{0,12}(?:生效|执行|采用).*");
        conflicts = input.planningContext() == null ? List.of() : input.planningContext().warnings().stream()
                .map(ComparisonMaterialDecision::parse).filter(java.util.Objects::nonNull)
                .filter(conflict -> raw.contains(conflict.subject())).toList();
    }

    /** 只以本次原始需求和服务端绑定冲突为依据，附件不能自行委派用户尚未作出的执行决定。 */
    static ComparisonMaterialDecision from(PlanningProviderRequest input) {
        return new ComparisonMaterialDecision(input);
    }

    /** 已明确为对照素材的同一成对冲突无需再要求择一；新对象、新来源取值仍不会借用该绑定。 */
    boolean coveredConflictQuestion(PlanQuestion question) {
        if (!comparisonOnly) return false;
        var candidate = parse(question.question());
        if (candidate == null || !conflicts.contains(candidate) || !knownParameters(question)) return false;
        String content = details(question);
        // 一题附带另一对象的选择时不能按第一个冲突吞掉整题；纯对照复合题另按完整任务范围判断。
        return conflicts.stream().filter(conflict -> !conflict.subject().equals(candidate.subject()))
                .noneMatch(conflict -> content.contains(conflict.subject()));
    }

    /** 表格方向、已知双方呈现和未知标记交给执行者；问题里的新增业务条件仍必须保留。 */
    boolean delegatedPresentation(PlanQuestion question) {
        if (!comparisonOnly || conflicts.isEmpty() || !knownParameters(question)) return false;
        String text = canonical(question.question());
        // 用户主动要求决定表格格式时仍提问；只读比较不等于系统可以代替所有业务选择。
        if (raw.matches("(?s).*(?:让我选择|由我选择|向我确认|询问我).{0,16}(?:结构|布局|行列|呈现|未知).*")
                || raw.matches("(?s).*请.{0,8}(?:确认|选择).{0,12}(?:结构|布局|行列).*")) return false;
        if (text.matches("^(?:差异表|规则对照表|对照表)(?:的)?(?:结构|行列布局|布局|列和行|行列)(?:应|应该|需要)?如何(?:组织|安排|设置)[？?]$")) {
            return onlyLayoutDetails(question);
        }
        if (raw.contains("保留未知") && raw.contains("不默认")
                && text.contains("未知") && text.matches("(?s).*(?:如何|怎样).*(?:呈现|标记|写明).*")
                && !text.matches("(?s).*(?:阈值设为|数值设为|采用什么|哪一|哪个).*")) return true;
        return text.matches("(?s).*(?:差异表|对照表).*(?:如何|怎样).*(?:呈现|展示|处理).*")
                && conflicts.stream().anyMatch(conflict -> text.contains(conflict.subject()));
    }

    /** 所有说明和候选共同核对；已知比较符/数值可呈现，第三值、新比较边界和独立属性不能被排版委派吞掉。 */
    private boolean knownParameters(PlanQuestion question) {
        String content = details(question);
        String known = conflicts.stream().map(conflict -> conflict.leftValue() + conflict.rightValue())
                .collect(java.util.stream.Collectors.joining());
        List<String> knownNumbers = NUMBER.matcher(known).results().map(match -> match.group()).toList();
        List<String> knownBoundaries = BOUNDARY.matcher(known).results().map(match -> match.group()).toList();
        // 路径里的版本号不是业务参数；完整规则中的已知责任要求也不是新增审批选择。
        String remaining = content;
        for (var conflict : conflicts) remaining = remaining.replace(conflict.leftPath(), "").replace(conflict.rightPath(), "")
                .replace(conflict.leftValue(), "").replace(conflict.rightValue(), "");
        if (INDEPENDENT.matcher(remaining).find()) return false;
        if (NUMBER.matcher(remaining).results().anyMatch(match -> !knownNumbers.contains(match.group()))) return false;
        if (BOUNDARY.matcher(remaining).results().anyMatch(match -> !knownBoundaries.contains(match.group()))) return false;
        return objectBoundParameters(question, remaining);
    }

    /** 数值必须仍属于原来的业务对象，退款不能借用订单的已知阈值；缺少对象绑定时保守保留问题。 */
    private boolean objectBoundParameters(PlanQuestion question, String remaining) {
        List<ComparedConflict> questionObjects = conflicts.stream()
                .filter(conflict -> conflict.mentions(canonical(question.question()))).toList();
        for (String clause : remaining.split("[。；;]")) {
            for (Pattern parameter : List.of(NUMBER, BOUNDARY)) {
                var match = parameter.matcher(clause);
                while (match.find()) {
                    ComparedConflict owner = null;
                    int nearest = -1;
                    String prefix = clause.substring(0, match.start());
                    for (var conflict : conflicts) {
                        int position = conflict.lastMention(prefix);
                        if (position > nearest) {
                            nearest = position;
                            owner = conflict;
                        }
                    }
                    // 单对象题可以让选项继承题干对象，复合题的未标注参数不能猜测属于哪一项。
                    if (owner == null && questionObjects.size() == 1) owner = questionObjects.getFirst();
                    if (owner == null || !parameter.matcher(owner.leftValue() + "。" + owner.rightValue())
                            .results().map(value -> value.group()).toList().contains(match.group())) return false;
                }
            }
        }
        return true;
    }

    /** 通用行列题的内容只允许布局词和本题对象；额外对象不能因题干是“表格结构”而被忽略。 */
    private boolean onlyLayoutDetails(PlanQuestion question) {
        String remaining = details(question);
        for (var conflict : conflicts) remaining = remaining.replace(conflict.subject(), "");
        return remaining.matches("(?:差异表|规则对照表|对照表|表格|结构|行列|布局|列和行|行|列|每行|每列|一个|每|是|规则|如"
                + "|方案甲|方案乙|第一份|第二份|方案|的|应|应该|需要|如何|组织|安排|设置|清晰|展示|便于|以便|确定|为|和|与|及"
                + "|单元格|显示|填写|对应|具体|条件|金额|阈值|来源|差异|取值|[，,。；;：:（）()\\[\\]？?、/])*" );
    }

    /** 题干、解释、选项与填写示例共同决定是否含新选择；不使用题号或模型名称删题。 */
    private static String details(PlanQuestion question) {
        StringBuilder value = new StringBuilder(question.question()).append('。').append(question.hint());
        question.options().forEach(option -> value.append('。').append(option.label()).append('。')
                .append(option.description()).append('。').append(option.answer()));
        question.examples().forEach(example -> value.append('。').append(example));
        return canonical(value.toString());
    }

    private static ComparedConflict parse(String text) {
        var match = CONFLICT.matcher(text == null ? "" : text);
        if (!match.find()) return null;
        String subject = canonical(match.group(1)).replaceFirst("(?:标准|规则|条件|阈值)$", "");
        return new ComparedConflict(subject, canonical(match.group(2)), canonical(match.group(3)), canonical(match.group(4)), canonical(match.group(5)));
    }

    /** 仅规范标点和空白；保留比较符的等于边界，不把大于与大于等于混为一谈。 */
    private static String canonical(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replace("≥", ">=").replace("≤", "<=");
    }

    private record ComparedConflict(String subject, String leftPath, String leftValue, String rightPath, String rightValue) {
        /** 保留标准名，同时识别完整证据中紧邻金额的同一对象简称，不借用其他对象的类别。 */
        private List<String> objectNames() {
            var match = Pattern.compile("^([\\p{L}]{2,12})(?:金额|数量|阈值)").matcher(leftValue);
            return match.find() ? List.of(subject, match.group(1)) : List.of(subject);
        }

        private boolean mentions(String text) {
            return objectNames().stream().anyMatch(text::contains);
        }

        private int lastMention(String text) {
            return objectNames().stream().mapToInt(text::lastIndexOf).max().orElse(-1);
        }
    }
}
