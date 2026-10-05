package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 继承原文已经确定的“并列差异、不采用其中一项”交付方式，保留完整资料取值与审批状态。
 * 仅覆盖具名旧／新材料的同一时间条件，不借相同数字消除其他对象或未决业务参数。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ReadOnlyMaterialComparison {
    private static final Pattern DIRECTIVE = Pattern.compile(
            "((?:旧|原)[^。\\r\\n]{2,160}[；;](?:新|另一)[^。\\r\\n]{2,160})[。]"
                    + "请(?:指出|列出|保留)差异[，,]不擅自采用其中一项[。]?");
    private static final Pattern VALUE = Pattern.compile("\\d+(?:\\.\\d+)?(?:小时|天|分钟)");
    private static final Pattern INDEPENDENT = Pattern.compile(
            "新增|另外|另一医院|跨医院|退款|金额|数据授权|生效日期|生效时间|观察窗口|统计单位|时区|适用人群"
                    + "|例外|特殊|不可抗力|工作日|节假日|周末|仅在|只有|若|如果|医院[AB甲乙]|机构[AB甲乙]");
    private final String statement;
    private final List<String> values;
    private final String object;

    private ReadOnlyMaterialComparison(String statement, List<String> values, String object) {
        this.statement = statement;
        this.values = List.copyOf(values);
        this.object = object;
    }

    /** 本次用户明确的只读交付才是决定，材料自己的“建议采用”不能给出选择授权。 */
    static ReadOnlyMaterialComparison from(String rawPrompt) {
        String raw = compact(rawPrompt);
        var directive = DIRECTIVE.matcher(raw);
        if (!raw.matches("(?s).*(?:只提供|仅提供|只给|只输出|不执行|只读).*")
                || raw.matches("(?s).*(?:本次必须|本次需要|还需|并需)(?:选定|选择|确定).{0,12}(?:生效|采用|执行).*")) {
            return new ReadOnlyMaterialComparison("", List.of(), "");
        }
        if (!directive.find()) return new ReadOnlyMaterialComparison("", List.of(), "");
        String text = directive.group();
        List<String> values = VALUE.matcher(text).results().map(match -> match.group()).distinct().toList();
        // 此有限语法只证明同一取消提前量，通用阈值或未给定对象不靠猜测关联。
        String object = text.contains("取消") ? "取消" : "";
        return new ReadOnlyMaterialComparison(text, values, object);
    }

    /** 原有材料选择已明确交给对照表；新对象、参数或授权仍必须向用户保留。 */
    boolean directedQuestion(PlanQuestion question) {
        if (statement.isEmpty() || values.size() != 2 || object.isEmpty()) return false;
        String heading = compact(question.question());
        if (!heading.matches(".*(?:处理|呈现|引用|采用|口径|如何定义).*")) return false;
        String content = heading + compact(question.hint());
        for (var option : question.options()) content += compact(option.label() + option.description() + option.answer());
        for (String example : question.examples()) content += compact(example);
        // “讨论时区分”是在描述区分材料，不是新增时区决定；真正的“按时区分组”继续保留。
        String decisionContent = content.replace("讨论时区分", "讨论中区分");
        // 问题可能将材料名放在提示和候选里；必须仍能证明是同一封闭的旧／新取值对。
        return content.contains(object) && content.contains("旧") && content.contains("新")
                && values.stream().allMatch(content::contains) && !INDEPENDENT.matcher(decisionContent).find()
                && VALUE.matcher(content).results().map(match -> match.group()).allMatch(values::contains);
    }

    /** 已确定的资料比较不标为用户未作选择；该原句仍随约束交付，不删除冲突事实。 */
    boolean directedReminder(String text) {
        if (statement.isEmpty()) return false;
        String value = compact(text);
        return value.equals(statement) || value.equals(compact("两份资料存在差异：") + statement);
    }

    /** 完整比较要求仍进入复制正文，不能只因免去提问而丢失双方取值或未批准状态。 */
    String guidance() { return statement.isEmpty() ? "" : "资料差异的既定交付：" + statement; }

    private static String compact(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }
}
