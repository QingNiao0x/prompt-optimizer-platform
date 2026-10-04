package com.promptoptimizer.enhancement.service.impl;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 比较简单问题的具体决定对象，保留属性、条件、数字和代码标识符。
 * 不把事实分类或相似关键词视为同一决定；复合问句由调用方保留，不做跨对象语义猜测。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanDecisionIdentity {
    private static final Pattern COMPOUND_OR_CHANGE = Pattern.compile(
            "以及|和|与|是否|更换|调整|迁移|冲突|还是|升级|降级|改为|转为|另外|此外");
    private static final Pattern QUESTION_GRAMMAR = Pattern.compile(
            "请确认本次采用哪一项|应如何|应该如何|采用什么|使用什么|按什么|是什么|是哪里|是多少"
                    + "|哪一项|哪一份|哪一种|哪种|哪个|哪些|哪里|什么|如何|怎样|怎么"
                    + "|本次|这次|这项|当前|具体|请问|请|采用|使用|覆盖的|的");

    private PlanDecisionIdentity() { }

    /** 简单问题仅按完整对象签名去重；属性相同、对象不同或新增条件都产生不同键。 */
    static String questionKey(String question) {
        if (COMPOUND_OR_CHANGE.matcher(question).find()) return null;
        String key = subject(question);
        return key.length() >= 2 ? key : null;
    }

    /** 服务端冲突题只能替代同一完整字段的简单追问，不能覆盖第三个取值或附加条件。 */
    static boolean repeatsConflict(String question, String field) {
        String key = questionKey(question);
        return key != null && key.equals(subject(field));
    }

    /** 仅匹配没有新增取值或子句的未决状态句，新的对象、时间和条件必须留在签名中。 */
    static boolean repeatsReminder(String reminder, String question) {
        var pending = Pattern.compile("^(.+?)(?:尚未确认|尚未明确|尚未确定|尚未提供|未确认|未明确|未确定|未提供|仍待确定|待确认)[。.!！?？]?$")
                .matcher(reminder);
        if (!pending.matches() || pending.group(1).matches("(?s).*[。；;：:\\r\\n].*")) return false;
        String key = questionKey(question);
        return key != null && key.equals(subject(pending.group(1)));
    }

    /** 列表中的名词标签也必须等于完整问题对象；括注只有与绑定答案完全等值时才可去除。 */
    static boolean repeatsDecisionLabel(String reminder, String question, String answer) {
        String label = reminder.strip().replaceFirst("[。.!！]$", "");
        var explanation = Pattern.compile("^([^（）()]+)[（(]([^（）()]+)[）)]$").matcher(label);
        if (explanation.matches()) {
            String detail = explanation.group(2).replaceAll("[\\s，,；;。]", "");
            String known = answer.replaceAll("[\\s，,；;。]", "");
            // 说明句只能由已提交答案中的完整子句构成，不能将新指标或另一组评审吞掉。
            if (!java.util.Arrays.stream(explanation.group(2).split("[，,；;。]"))
                    .map(part -> part.replaceAll("[\\s，,；;。]", ""))
                    .filter(part -> !part.isEmpty()).allMatch(known::contains)) return false;
            if (detail.isEmpty()) return false;
            label = explanation.group(1);
        }
        if (label.matches("(?s).*[。；;：:\\r\\n？?].*")) return false;
        String key = questionKey(question);
        return key != null && key.equals(subject(label));
    }

    /** 仅归一化确定的提问语法及地区问法；不去掉金额、范围限定、比较符和来源。 */
    private static String subject(String text) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        // 仅规范化同一属性的询问/陈述语序，保留人数、研究组、附加范围和所有数值。
        value = value.replace("最多允许几轮提问", "最大提问轮次")
                .replaceAll("评分出现分歧时[，,]?(?:应如何|如何)处理", "评分分歧处理方式")
                .replace("评分出现分歧时的处理方式", "评分分歧处理方式")
                .replace("之间的一致性应如何评价", "之间一致性评价方式")
                .replace("之间一致性的评价方式", "之间一致性评价方式");
        value = QUESTION_GRAMMAR.matcher(value).replaceAll("")
                .replaceAll("[\\s，。；：！？、‘’“”!?;,:\"']+", "");
        // 两种常见地区问法对应同一研究范围；“患者地区”“数据地区”仍保留其完整对象。
        value = value.replaceAll("研究(?:覆盖)?(?:地区|区域)(?:范围)?", "研究地区范围");
        return value.replace("规则标准", "标准");
    }
}
