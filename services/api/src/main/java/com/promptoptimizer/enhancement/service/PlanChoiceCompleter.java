package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;

import java.util.ArrayList;
import java.util.List;

/**
 * 只把模型给出的示例收成可点选项，不另造与问题无关的通用下一步。
 * 已有「建议」时只保留第一项，避免把举例里的地名或框架自动标成建议。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanChoiceCompleter {

    static final int MIN_OPTIONS = 4;

    private PlanChoiceCompleter() {
    }

    static PlanQuestion complete(PlanQuestion question) {
        List<PlanOption> options = new ArrayList<>();
        if (question.options() != null) {
            options.addAll(question.options());
        }
        if (question.examples() != null) {
            int exampleIndex = 1;
            for (String example : question.examples()) {
                if (options.size() >= MIN_OPTIONS || example == null || example.isBlank()) {
                    continue;
                }
                String text = example.trim();
                options.add(new PlanOption(
                        question.id() + "-example-" + exampleIndex++,
                        shorten(text, 40),
                        "这是该问题的一个具体答案",
                        text,
                        false
                ));
            }
        }
        boolean recommendedSeen = false;
        List<PlanOption> completed = new ArrayList<>();
        for (PlanOption option : options) {
            boolean recommended = option.recommended() && !recommendedSeen;
            if (recommended) {
                recommendedSeen = true;
            }
            completed.add(new PlanOption(
                    option.id(),
                    option.label(),
                    option.description(),
                    option.answer(),
                    recommended
            ));
        }
        PlanQuestionType type = question.type() == PlanQuestionType.MULTIPLE_CHOICE
                ? PlanQuestionType.MULTIPLE_CHOICE
                : PlanQuestionType.SINGLE_CHOICE;
        return new PlanQuestion(
                question.id(),
                question.question(),
                question.hint(),
                type,
                completed,
                List.of(),
                true
        );
    }

    static boolean presentable(PlanQuestion question) {
        long recommended = question.options().stream().filter(PlanOption::recommended).count();
        return question.options().size() >= MIN_OPTIONS && recommended == 1;
    }

    private static String shorten(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max).stripTrailing() + "…";
    }
}
