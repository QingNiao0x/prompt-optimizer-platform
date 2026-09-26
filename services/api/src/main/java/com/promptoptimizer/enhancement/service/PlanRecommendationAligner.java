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
    private static final int MINIMUM_EVIDENCE = 2;
    /** 两个字的常见词不能单独把「建议」改到某个举例上。 */
    private static final Set<String> WEAK_TOKENS = Set.of(
            "地区", "范围", "研究", "分析", "使用", "项目", "需求", "结果", "问题", "什么", "哪些", "如何", "以及"
    );

    private PlanRecommendationAligner() {
    }

    static PlanQuestion align(PlanQuestion question, PlanningProviderRequest input) {
        if (question.type() == PlanQuestionType.FREE_TEXT || question.options().size() < 2) {
            return question;
        }
        String userCorpus = userCorpus(input).toLowerCase(Locale.ROOT);
        String projectCorpus = projectCorpus(input).toLowerCase(Locale.ROOT);
        if (userCorpus.isBlank() && projectCorpus.isBlank()) {
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
        for (int index = 0; index < tokenSets.size(); index++) {
            int score = 0;
            for (String token : tokenSets.get(index)) {
                if (shared.contains(token) || WEAK_TOKENS.contains(token)) {
                    continue;
                }
                // 用户当前需求和已说出口的偏好优先于项目材料里的旁证。
                if (userCorpus.contains(token)) {
                    score += 2;
                } else if (projectCorpus.contains(token)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
                unique = true;
            } else if (score == bestScore && score > 0) {
                unique = false;
            }
        }
        if (!unique || bestIndex < 0 || bestScore < MINIMUM_EVIDENCE) {
            return question;
        }
        List<PlanOption> aligned = new ArrayList<>();
        for (int index = 0; index < question.options().size(); index++) {
            PlanOption option = question.options().get(index);
            aligned.add(new PlanOption(
                    option.id(),
                    option.label(),
                    option.description(),
                    option.answer(),
                    index == bestIndex
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
        append(corpus, input.rawPrompt());
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
        Matcher matcher = TOKEN.matcher((option.label() + " " + option.description() + " " + option.answer())
                .toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            tokens.add(token);
            if (token.codePoints().allMatch(Character::isIdeographic) && token.length() > 2) {
                for (int index = 0; index + 2 <= token.length(); index++) {
                    tokens.add(token.substring(index, index + 2));
                }
            }
        }
        return tokens;
    }

    private static void append(StringBuilder corpus, String value) {
        if (value != null && !value.isBlank()) {
            corpus.append('\n').append(value);
        }
    }
}
