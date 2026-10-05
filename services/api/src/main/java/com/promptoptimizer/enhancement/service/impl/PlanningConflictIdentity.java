package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 以字段、成对来源和完整规则绑定同一次材料冲突，不凭类别、相同数字或业务名中的常用词合并。
 * 支持明确金额比较符的等义写法；无法证明对象、效果和边界相同的描述仍视为独立问题。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanningConflictIdentity {
    private static final Pattern CONFLICT = Pattern.compile(
            "资料对“([^”]{2,40})”存在不同取值：(.+?)（([^）]+)）与 (.+?)（([^）]+)）");
    private final String field;
    private final String leftPath;
    private final String leftValue;
    private final String rightPath;
    private final String rightValue;

    private PlanningConflictIdentity(String field, String leftPath, String leftValue, String rightPath, String rightValue) {
        this.field = field;
        this.leftPath = leftPath;
        this.leftValue = leftValue;
        this.rightPath = rightPath;
        this.rightValue = rightValue;
    }

    /** 只解析服务端生成且证据完整的成对冲突文案；截断或缺失来源时不推断绑定。 */
    static Optional<PlanningConflictIdentity> parse(String text) {
        var match = CONFLICT.matcher(text == null ? "" : text);
        if (!match.find()) return Optional.empty();
        return Optional.of(new PlanningConflictIdentity(match.group(1), match.group(2), match.group(3), match.group(4), match.group(5)));
    }

    /**
     * 模型改写题必须仍在同一规则对象和属性上，并完整提供双方已知选项。
     * 新对象、条件或第三个值不合并；调用方还需保证只匹配一个绑定冲突。
     */
    boolean matchesQuestion(PlanQuestion question) {
        String text = canonical(question.question());
        if (question.type() != PlanQuestionType.SINGLE_CHOICE || hasNewCondition(text) || hasNewNumber(text)
                || !text.matches(".*(?:采用|选择|哪一|哪个|哪种|版本).*")) return false;
        var left = RuleBoundary.parse(leftValue);
        var right = RuleBoundary.parse(rightValue);
        if (left.isPresent() && right.isPresent()) {
            // 问句可省略已在完整选项中明确的复核效果，但对象和金额属性不能省略或更换。
            if (!left.get().sameDimension(right.get()) || !text.contains(left.get().object()) || !text.contains(left.get().property())) return false;
        } else {
            if (!mentionsScalarField(text)) return false;
        }
        List<String> matched = new ArrayList<>();
        for (var option : question.options()) {
            String answer = canonical(option.answer());
            if (hasNewCondition(answer) || hasNewNumber(answer)) return false;
            if (answer.matches("^(?:暂不|尚未|未|待|不知道|不清楚).*") || answer.matches(".*(?:保留|标明|写明|等待).*冲突.*")) continue;
            if (answer.matches("^(?:本次)?(?:不选定|暂不选定|暂不采用|尚不选择).*(?:保留|并列|待|确认).*")) continue;
            Optional<String> selected = selectedValue(option.answer());
            if (selected.isEmpty()) return false;
            matched.add(selected.get());
        }
        return matched.contains(canonical(leftValue)) && matched.contains(canonical(rightValue));
    }

    /** 金额阈值问句可在对象和属性之间插入“方案”等语法，不能只凭两个相同数值借用别的对象。 */
    private boolean mentionsScalarField(String text) {
        String label = canonical(field).replace("金额阈值", "阈值");
        if (text.replace("金额阈值", "阈值").contains(label)) return true;
        var amount = Pattern.compile("^([\\p{IsHan}a-z_]{2,24})(金额阈值)$").matcher(canonical(field));
        return amount.matches() && text.contains(amount.group(1)) && text.contains(amount.group(2));
    }

    /** 未选择的待定选项也不能偷偷加入第三个金额；数字边界与小数按完整词匹配。 */
    private boolean hasNewNumber(String text) {
        Pattern number = Pattern.compile("\\d+(?:\\.\\d+)?|[零一二三四五六七八九十百千万亿点]+(?=元|万|秒)");
        List<String> known = number.matcher(canonical(leftValue + " " + rightValue)).results().map(match -> match.group()).toList();
        return number.matcher(text).results().anyMatch(match -> !known.contains(match.group()));
    }

    /** 明确选择须唯一命中完整来源取值；比较符和小数保留，否定候选、并列选择及新条件不猜测。 */
    Optional<String> selectedValue(String answer) {
        String text = canonical(answer);
        if (text.equals(canonical(leftValue))) return Optional.of(canonical(leftValue));
        if (text.equals(canonical(rightValue))) return Optional.of(canonical(rightValue));
        List<String> selected = new ArrayList<>();
        for (String sentence : text.split("[。；;\\n]")) {
            boolean conditionalPrefix = false;
            for (String originalClause : sentence.split("[，,]")) {
                String clause = selectionClause(originalClause);
                // 逗号不能切断“如果批准，采用A”的前提；另一完整句仍可给出独立明确选择。
                if (clause.matches(".*(?:如果|假如|仅当|前提是|须待|若.{0,20}批准).*")) conditionalPrefix = true;
                if (conditionalPrefix || !clause.matches("^(?:本次|最终|请|决定)?(?:以|采用|用|使用|选择|选定|按照|按).+")) continue;
                if (clause.matches(".*(?:不采用|不用|不选|不要|不以|不能|未确定|如果|假如|可能|或者|还是|是否|待确认|再确认|都可以).*")) continue;
                if (hasNewCondition(clause)) continue;
                for (String value : List.of(leftValue, rightValue)) {
                    if (containsCompleteValue(clause, canonical(value))
                            || RuleBoundary.parse(value).filter(rule -> rule.matchesSelection(clause)).isPresent()) {
                        selected.add(canonical(value));
                    }
                }
            }
        }
        var unique = selected.stream().distinct().toList();
        return unique.size() == 1 ? Optional.of(unique.getFirst()) : Optional.empty();
    }

    /**
     * 自定义长回答可包含列表、强调和“我的最终决定是”；只规范化明确选择句的展示前缀。
     * 不去掉否定、条件、机构名、数值或比较符，未决和多值选择仍由原校验拒绝。
     */
    private static String selectionClause(String text) {
        return text.replaceAll("[`*]", "")
                .replaceFirst("^(?:[-#]+|\\d+[.)、])", "")
                .replaceFirst("^(?:我的|用户的)?(?:本次|最终)?(?:明确)?(?:选择|决定|结论|答复)(?:是|为)?[：:]", "")
                .replaceFirst("^(?:本次|最终)?(?:已确认|确认)[：:]?(?=采用|使用|选择|选定|以|按)", "");
    }

    /** 只有绑定时的完整来源和取值对可被旧选择解决；新来源即使复述旧值仍需核对。 */
    boolean containsPair(String candidateField, String firstPath, String firstValue, String secondPath, String secondValue) {
        if (!field.equals(candidateField)) return false;
        return sameEvidence(leftPath, leftValue, firstPath, firstValue) && sameEvidence(rightPath, rightValue, secondPath, secondValue)
                || sameEvidence(leftPath, leftValue, secondPath, secondValue) && sameEvidence(rightPath, rightValue, firstPath, firstValue);
    }

    boolean hasValues(String candidateField, List<String> values) {
        return field.equals(candidateField) && values.size() == 2
                && values.stream().map(PlanningConflictIdentity::canonical).distinct().sorted().toList()
                .equals(List.of(canonical(leftValue), canonical(rightValue)).stream().distinct().sorted().toList());
    }

    private boolean sameEvidence(String path, String value, String candidatePath, String candidateValue) {
        return path.replaceFirst("#chunk-\\d+$", "").equals(candidatePath.replaceFirst("#chunk-\\d+$", ""))
                && canonical(value).equals(canonical(candidateValue));
    }

    /** 不把普通审批选择扩大为退款、特殊订单或生效时间；原证据自身已包含的条件不在此排除。 */
    private boolean hasNewCondition(String text) {
        String known = canonical(field + leftValue + rightValue);
        if (Pattern.compile("退款|紧急|例外|特殊|历史|新增|生效日期|生效时间|其他机构|另一机构|仅适用|仅针对|仅限|新合同|新订单")
                .matcher(text).results().anyMatch(match -> !known.contains(match.group()))) return true;
        // 明确写出的机构名是作用对象，不能因双方金额和比较符相同而借用另一机构的决定。
        return Pattern.compile("[\\p{IsHan}]{2,16}(?:中心|医院|公司|部门|机构)").matcher(text).results()
                .map(match -> match.group().replaceFirst("^(?:(?:本次|当前|采用|使用|选择|选定|为|在|按|以|由|请))+", ""))
                .filter(owner -> !owner.matches(".*(?:哪个|哪些|何种|什么|审批机构|复核部门|处理部门).*"))
                .anyMatch(owner -> !known.contains(owner));
    }

    private boolean containsCompleteValue(String text, String value) {
        return Pattern.compile("(?<![a-z0-9零一二三四五六七八九十百千万亿点.<>])" + Pattern.quote(value)
                + "(?![a-z0-9零一二三四五六七八九十百千万亿点.=>])").matcher(text).find();
    }

    /** 只做保留边界的等义规范化，不能像标点清理一样抹掉 >、>=、小数或业务对象。 */
    static String canonical(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "").replace("大于或等于", ">=").replace("大于等于", ">=")
                .replace("小于或等于", "<=").replace("小于等于", "<=").replace("严格大于", ">")
                .replace("严格小于", "<").replace("大于", ">").replace("小于", "<")
                .replace("≥", ">=").replace("≤", "<=").replaceFirst("[。.!！]$", "");
    }

    /** 机构标签只是归属，同一机构的审批金额与退款金额按各自对象、属性和效果分组。 */
    static String businessDimension(String field, String value) {
        if (!field.matches(".*(?:中心|医院|公司|部门|机构)$")) return "";
        return RuleBoundary.parse(value).map(rule -> rule.object() + "\u0000" + rule.property() + "\u0000" + rule.effect()).orElse("");
    }

    /** 仅适配可逐项核对的金额规则，不将含额外适用条件的专业句子压成同一个数字键。 */
    private record RuleBoundary(String object, String property, String comparator, String amount, String unit, String effect) {
        static Optional<RuleBoundary> parse(String value) {
            var match = Pattern.compile("^([\\p{IsHan}a-z_]{2,24}?)(金额)(>=|<=|>|<|=)(\\d+(?:\\.\\d+)?)(元)?"
                    + "(?:时|才|则|需|触发|进行|进入)?(二级复核|财务复核|复核)$").matcher(canonical(value));
            if (!match.matches()) return Optional.empty();
            return Optional.of(new RuleBoundary(match.group(1), match.group(2), match.group(3), match.group(4),
                    match.group(5) == null ? "" : match.group(5), match.group(6)));
        }

        boolean sameDimension(RuleBoundary other) {
            return object.equals(other.object) && property.equals(other.property) && unit.equals(other.unit) && effect.equals(other.effect);
        }

        boolean mentionsDimension(String text) {
            return text.contains(object) && text.contains(property) && text.contains(effect);
        }

        boolean matchesSelection(String text) {
            if (!mentionsDimension(text) || text.matches(".*(?:不|禁止|无需)" + Pattern.quote(effect) + ".*")) return false;
            return Pattern.compile("(?<![<>=0-9.])" + Pattern.quote(comparator + amount + unit)
                    + "(?![0-9.])").matcher(text).find();
        }
    }
}
