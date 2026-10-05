package com.promptoptimizer.enhancement.service.impl;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 按决定的对象、属性和完整限定识别有限的语序改写，不用行业或事实类别代替对象。
 * 泛指选项只有在用户原文逐字包含该完整选择时才兼容；新增条件、取值和对象保守保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PendingDecisionSignature {
    private static final Pattern PRIMARY_QUESTION = Pattern.compile(
            "^(.+?)(?:应|应该)?以哪一(?:版|份)(.+?)(?:作为|为)(主文本|基准文本|主方案)$");
    private static final Pattern PRIMARY_STATEMENT = Pattern.compile(
            "^(?:两版|两份)(.+?)中哪一(?:版|份)是(.+?)的(主文本|基准文本|主方案)$");
    private static final Pattern UNASSIGNED_PRIMARY = Pattern.compile(
            "^(?:两版|两份)(.+?)(?:尚未|仍未|未)(?:指定|选择)(主文本|基准文本|主方案)$");
    private static final Pattern UNIT_BEFORE = Pattern.compile(
            "^(.+?)时[，,]?(?:主统计单位)(?:应|应该)?(?:使用|采用)(.+)$");
    private static final Pattern UNIT_AFTER = Pattern.compile(
            "^(.+?)时[，,]?(?:使用|采用)(.+?)(?:作为|为)主统计单位$");
    private static final Pattern FOCUS = Pattern.compile("^(.+?)(?:应|应该)?(?:优先)?侧重(.+)$");
    private static final Pattern NEW_QUALIFIER = Pattern.compile(
            "[0-9<>≤≥=]|另外|此外|新增|仅在|只有|跨境|跨租户|若|如果|当|之后|之前|生效|退款");

    private PendingDecisionSignature() { }

    /** 完整槽位吻合才能合并；泛指与具体选择的关联额外核对用户原文，而不读取模型推荐。 */
    static boolean same(String candidate, String question, String rawPrompt) {
        String left = clean(candidate), right = clean(question);
        Optional<Identity> observed = primary(left).or(() -> unit(left));
        Optional<Identity> known = primary(right).or(() -> unit(right));
        if (observed.isPresent() && known.isPresent()) return observed.get().equals(known.get());
        var leftFocus = FOCUS.matcher(left);
        var rightFocus = FOCUS.matcher(right);
        if (leftFocus.matches() && rightFocus.matches()
                && focusOwner(leftFocus.group(1), rawPrompt).equals(focusOwner(rightFocus.group(1), rawPrompt))) {
            String choice = leftFocus.group(2), boundChoice = rightFocus.group(2);
            if (choice.equals(boundChoice)) return true;
            // 泛指安排只绑定原文已完整命名的候选对；财务复核、跨境条件等仍是独立决定。
            return boundChoice.matches("哪(?:一)?(?:类|方面)(?:的)?安排")
                    && !NEW_QUALIFIER.matcher(choice).find()
                    && Pattern.compile("侧重([^。？?\\r\\n]+)").matcher(clean(rawPrompt)).results()
                    .map(match -> focusChoice(match.group(1))).anyMatch(focusChoice(choice)::equals);
        }
        // 仅复用原题逐字命名的计划确认组，不能给另一研究组或新轮数借用身份。
        String planSubject = planSubject(right);
        return !planSubject.isEmpty() && planSubject.equals(planSubject(left));
    }

    /** 主文本的活动范围与被比较文档同时参与身份；草稿A/B等新版本不会被消掉。 */
    private static Optional<Identity> primary(String text) {
        var question = PRIMARY_QUESTION.matcher(text);
        if (question.matches()) return Optional.of(new Identity(owner(question.group(1)),
                question.group(3), question.group(2)));
        var statement = PRIMARY_STATEMENT.matcher(text);
        return statement.matches() ? Optional.of(new Identity(owner(statement.group(2)),
                statement.group(3), statement.group(1))) : Optional.empty();
    }

    /** 仅完整文档对象和主文本属性吻合时，长回答产生的未决子项才可继承同一绑定原题的范围。 */
    static boolean primaryPartOfOriginal(String part, String original) {
        var pending = UNASSIGNED_PRIMARY.matcher(clean(part));
        var anchor = primary(clean(original));
        return pending.matches() && anchor.isPresent()
                && pending.group(1).equals(anchor.get().qualifier())
                && pending.group(2).equals(anchor.get().property());
    }

    /** 统计单位候选保留完整文字，不能把患者与事件或另一渠道当作同一口径。 */
    private static Optional<Identity> unit(String text) {
        var before = UNIT_BEFORE.matcher(text);
        if (before.matches()) return Optional.of(new Identity(owner(before.group(1)), "主统计单位", before.group(2)));
        var after = UNIT_AFTER.matcher(text);
        return after.matches() ? Optional.of(new Identity(owner(after.group(1)), "主统计单位", after.group(2)))
                : Optional.empty();
    }

    /** 仅统一同一交互式计划的提问语法，最大轮次和终止规则两项均须存在。 */
    private static String planSubject(String text) {
        String value = text.replaceFirst("^计划确认组在预注册中(?:应|应该)?(?:设定|设置)(?:怎样|什么)的", "交互式计划确认的")
                .replaceFirst("^交互式计划确认组的", "交互式计划确认的")
                .replaceFirst("(?:尚未|仍未)预注册$", "")
                .replaceFirst("(?:应|应该)?如何预注册$", "")
                .replace("最大提问轮次", "最大轮次").replace("最大轮次与终止规则", "最大轮次和终止规则");
        return value.equals("交互式计划确认的最大轮次和终止规则") ? value : "";
    }

    /** 只移除已知提问前缀和状态后缀，所有数值、条件、路径、代码及选项文字保持不变。 */
    private static String clean(String text) {
        String value = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").replaceAll("[。；;？?]+$", "");
        return value.replaceFirst("(?:当前|目前)?(?:尚未|仍未|未)(?:确定|决定|明确|选择|选定)$", "");
    }

    private static String owner(String value) { return value.replaceFirst("^(?:本次|这次)", ""); }

    /** 原文明确交给律师的同一次复核才允许省略职业名，不能把财务等另一复核对象借用过来。 */
    private static String focusOwner(String value, String rawPrompt) {
        String normalized = owner(value).replaceFirst("(?:尚未|仍未)(?:选定|确定)$", "");
        return normalized.equals("复核") && clean(rawPrompt).contains("供律师复核")
                ? "律师复核" : normalized;
    }

    /** 只统一该候选对结尾的安排语法，不改写候选名称或条件。 */
    private static String focusChoice(String value) { return value.replaceFirst("(?:的)?安排$", ""); }

    /** 同一属性不能跨文档对象或活动范围共享身份。 */
    private record Identity(String object, String property, String qualifier) { }
}
