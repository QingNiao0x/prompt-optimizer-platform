package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在不增删候选项的前提下，把「建议」对准当前需求、已上传材料和用户已说过的话。
 * 证据不能唯一指向某一项时，保留模型原来的建议，避免替用户改方向。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanRecommendationAligner {

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9][a-z0-9+_.-]{2,}|[\\p{IsHan}]{2,}");
    private static final int MINIMUM_EVIDENCE = 1;
    /** 两个字的常见词不能单独把「建议」改到某个举例上。 */
    private static final Set<String> WEAK_TOKENS = Set.of(
            "地区", "范围", "研究", "分析", "使用", "项目", "需求", "结果", "问题", "什么", "哪些", "如何", "以及"
    );

    private PlanRecommendationAligner() {
    }

    /** 当前目标优先于历史和项目材料；排除明确否定的技术，推荐只作待确认建议。 */
    static PlanQuestion align(PlanQuestion question, PlanningProviderRequest input) {
        if (question.type() == PlanQuestionType.FREE_TEXT || question.options().size() < 2) {
            return question;
        }
        String currentCorpus = input.rawPrompt().toLowerCase(Locale.ROOT);
        String userCorpus = userCorpus(input).toLowerCase(Locale.ROOT);
        String projectCorpus = projectCorpus(input).toLowerCase(Locale.ROOT);
        if (currentCorpus.isBlank() && userCorpus.isBlank() && projectCorpus.isBlank()) {
            return question;
        }
        List<Set<String>> tokenSets = question.options().stream()
                .map(PlanRecommendationAligner::tokens)
                .toList();
        Set<String> shared = new HashSet<>(tokenSets.getFirst());
        for (Set<String> tokens : tokenSets) {
            shared.retainAll(tokens);
        }
        int bestScore = 0;
        int bestIndex = -1;
        boolean unique = true;
        List<Boolean> excluded = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (int index = 0; index < tokenSets.size(); index++) {
            int score = 0;
            int sourceWeight = 0;
            boolean rejected = false;
            Set<String> matched = new java.util.TreeSet<>();
            String label = question.options().get(index).label().toLowerCase(Locale.ROOT);
            for (String token : tokenSets.get(index)) {
                if (shared.contains(token) || WEAK_TOKENS.contains(token)) {
                    continue;
                }
                int currentEvidence = evidence(currentCorpus, token);
                if (currentEvidence < 0) {
                    rejected = true;
                    break;
                }
                // 描述中的任务主题不代表选择偏好，例如提到 Arriaga 不等于“只要关键代码”。
                int weight = label.contains(token) && currentEvidence > 0 ? 10_000
                        : label.contains(token) && evidence(userCorpus, token) > 0 ? 100
                        : evidence(projectCorpus, token) > 0 ? 1 : 0;
                if (weight > sourceWeight) {
                    sourceWeight = weight;
                    score = 0;
                    matched.clear();
                }
                if (weight > 0 && weight == sourceWeight) {
                    score += weight;
                    matched.add(token);
                }
            }
            excluded.add(rejected);
            reasons.add((sourceWeight == 10_000 ? "原始需求已提及" : sourceWeight == 100
                    ? "项目描述或历史偏好已提及" : "已分析的项目技术栈、依赖或文件包含")
                    + "：" + String.join("、", matched.stream().limit(3)
                    .map(token -> token.length() > 64 ? token.substring(0, 64) : token).toList())
                    + "。建议优先核对这项兼容方案。");
            if (rejected) score = 0;
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
                unique = true;
            } else if (score == bestScore && score > 0) {
                unique = false;
            }
        }
        boolean supported = unique && bestIndex >= 0 && bestScore >= MINIMUM_EVIDENCE;
        List<PlanOption> aligned = new ArrayList<>();
        for (int index = 0; index < question.options().size(); index++) {
            PlanOption option = question.options().get(index);
            boolean recommended = !excluded.get(index) && (supported ? index == bestIndex : option.recommended());
            aligned.add(new PlanOption(
                    option.id(),
                    option.label(),
                    option.description(),
                    option.answer(),
                    recommended,
                    recommended ? (supported ? reasons.get(index) : option.recommendationReason()) : ""
            ));
        }
        return new PlanQuestion(
                question.id(),
                question.question(),
                question.hint(),
                question.type(),
                aligned,
                question.examples(),
                question.allowCustomAnswer()
        );
    }

    private static String userCorpus(PlanningProviderRequest input) {
        StringBuilder corpus = new StringBuilder();
        append(corpus, input.contextDescription());
        input.conversationHistory().stream()
                .filter(message -> "user".equals(message.role()))
                .forEach(message -> append(corpus, message.content()));
        return corpus.toString();
    }

    private static String projectCorpus(PlanningProviderRequest input) {
        StringBuilder corpus = new StringBuilder();
        if (input.planningContext() == null) {
            return "";
        }
        input.planningContext().technologies().forEach(value -> append(corpus, value));
        input.planningContext().dependencies().forEach(value -> append(corpus, value));
        input.planningContext().fileSummaries().forEach(value -> append(corpus, value));
        input.planningContext().factCards().forEach(card -> {
            append(corpus, card.sourcePath());
            append(corpus, card.evidence());
        });
        return corpus.toString();
    }

    private static Set<String> tokens(PlanOption option) {
        Set<String> tokens = new HashSet<>();
        String optionText = (option.label() + " " + option.description() + " " + option.answer())
                .toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN.matcher(optionText);
        while (matcher.find()) {
            String token = matcher.group();
            if (evidence(optionText, token) < 0) continue;
            tokens.add(token);
            if (token.codePoints().allMatch(Character::isIdeographic) && token.length() > 4) {
                for (int index = 0; index + 4 <= token.length(); index++) {
                    tokens.add(token.substring(index, index + 4));
                }
            }
        }
        return tokens;
    }

    /** 只使用完整技术词或中文短语；明确的否定和迁出来源不构成正向推荐依据。 */
    private static int evidence(String corpus, String token) {
        boolean latin = token.chars().allMatch(character -> character < 128);
        Pattern occurrence = Pattern.compile((latin ? "(?<![a-z0-9])" : "") + Pattern.quote(token)
                + (latin ? "(?![a-z0-9])" : ""));
        Matcher matcher = occurrence.matcher(corpus);
        boolean found = false;
        while (matcher.find()) {
            String prefix = corpus.substring(Math.max(0, matcher.start() - 24), matcher.start());
            String suffix = corpus.substring(matcher.end(), Math.min(corpus.length(), matcher.end() + 24));
            if (prefix.matches("(?s).*(?:不要|不再|不能|不使用|不采用|不引入|尚未|无需|排除|避免|禁止|without|avoid|not)[^，。；;.!?\\n]{0,12}")
                    || prefix.matches("(?s).*从\\s*") && suffix.matches("(?s)^.{0,6}(?:迁移到|切换到|改为|替换为).*") ) {
                return -1;
            }
            found = true;
        }
        return found ? 1 : 0;
    }

    private static void append(StringBuilder corpus, String value) {
        if (value != null && !value.isBlank()) {
            corpus.append('\n').append(value);
        }
    }
}
