package com.promptoptimizer.enhancement.service.impl;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 将完整条件句与同条件的候选名词短语关联，不消除地区对象、否定、数值或额外条件。
 * 只识别明确的“候选如何处理”句式，不作为跨行业相似度过滤器。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ConditionalCandidateDecision {
    private static final String RECORD = "(?:候选记录|候选|记录)";
    private static final String ARTICLE = "(?:(?:某条|某个|这条|该|此))?";
    private static final String ACTION = "应(?:如何|怎样)处理(?:" + ARTICLE + RECORD + ")?";
    private static final Pattern CONDITIONAL = Pattern.compile("^(?:当)?" + ARTICLE + RECORD + "(?:的)?(.{2,180})时[，,]"
            + "(?:" + ARTICLE + RECORD + ")?" + ACTION + "[？?]?$" );
    private static final Pattern ATTRIBUTIVE = Pattern.compile("^(.{2,180})的" + RECORD + ACTION
            + "(?:[？?]|[，,](?:目前|当前)?(?:尚未|未)(?:决定|确定).*)?$" );

    private ConditionalCandidateDecision() { }

    /** 条件按完整原文比较；“缺失”和“不缺失”、新增天数和另一地区绝不等值。 */
    static boolean same(String first, String second) {
        Optional<String> left = condition(first);
        return left.isPresent() && left.equals(condition(second));
    }

    /** 仅取处理问句的完整条件，附加来源说明由调用方完整保留。 */
    private static Optional<String> condition(String text) {
        var sentence = CONDITIONAL.matcher(text.strip());
        if (sentence.matches()) return Optional.of(canonical(sentence.group(1)));
        var phrase = ATTRIBUTIVE.matcher(text.strip());
        return phrase.matches() ? Optional.of(canonical(phrase.group(1))) : Optional.empty();
    }

    private static String canonical(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
