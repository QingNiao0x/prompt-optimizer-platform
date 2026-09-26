package com.promptoptimizer.enhancement.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从已脱敏的文档片段中选取少量业务事实供计划提问使用。 */
final class PlanningDocumentExcerpt {
    private static final Pattern TERMS = Pattern.compile("[a-zA-Z0-9_]{3,}|[\\p{IsHan}]{2,}");
    private static final Pattern SENTENCES = Pattern.compile("(?<=[。！？!?；;])|\\R+");
    private static final Pattern PROTECTED_PATH = Pattern.compile(
            "(?i)\\.env(?:\\.[\\w.-]+)?|\\S+\\.(?:pem|key)\\b|application[-.](?:prod|production)\\.(?:yml|yaml|properties|json)"
    );
    private static final List<String> RULE_TERMS = List.of(
            "必须", "应当", "超过", "仅当", "否则", "规则", "条件", "流程", "验收", "阈值"
    );

    private PlanningDocumentExcerpt() { }

    static String select(String content, String query, int maxCharacters) {
        if (content == null || content.isBlank() || maxCharacters <= 0
                || content.startsWith("[CONTENT REDACTED:")) return "";
        String[] parts = SENTENCES.split(content);
        List<String> candidates = new ArrayList<>();
        for (String part : parts) {
            String sentence = normalize(part);
            if (!sentence.isBlank() && !PROTECTED_PATH.matcher(sentence).find()) candidates.add(sentence);
        }
        if (candidates.isEmpty()) return "";
        String normalized = String.join(" ", candidates);
        if (normalized.length() <= maxCharacters) return normalized;
        if (candidates.size() == 1) return sampleUnstructuredLine(normalized, maxCharacters);
        Set<String> terms = terms(query);
        List<Integer> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) ranked.add(i);
        ranked.sort(Comparator.<Integer>comparingInt(i -> score(candidates.get(i), terms))
                .reversed().thenComparingInt(i -> i));

        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        ranked.stream().limit(3).forEach(selected::add);
        selected.add(0);
        selected.add(candidates.size() / 2);
        selected.add(candidates.size() - 1);
        StringBuilder excerpt = new StringBuilder();
        for (int index : selected) {
            String sentence = candidates.get(index);
            if (sentence.length() > 220) sentence = sentence.substring(0, 220) + "…";
            int remaining = maxCharacters - excerpt.length();
            if (remaining <= 0) break;
            if (!excerpt.isEmpty()) {
                if (remaining <= 1) break;
                excerpt.append('；');
                remaining--;
            }
            excerpt.append(sentence, 0, Math.min(sentence.length(), remaining));
        }
        return excerpt.toString();
    }

    private static String sampleUnstructuredLine(String content, int maxCharacters) {
        if (maxCharacters < 5) return content.substring(0, maxCharacters);
        int window = (maxCharacters - 2) / 3;
        int middle = (content.length() - window) / 2;
        return content.substring(0, window) + "…"
                + content.substring(middle, middle + window) + "…"
                + content.substring(content.length() - window);
    }

    private static Set<String> terms(String query) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TERMS.matcher(query == null ? "" : query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            result.add(word);
            if (word.matches("[\\p{IsHan}]+")) {
                for (int i = 0; i + 2 <= word.length(); i++) result.add(word.substring(i, i + 2));
            }
        }
        return result;
    }

    private static int score(String sentence, Set<String> terms) {
        String normalized = sentence.toLowerCase(Locale.ROOT);
        int relevance = (int) terms.stream().filter(normalized::contains).count();
        int rules = (int) RULE_TERMS.stream().filter(normalized::contains).count();
        return relevance * 3 + rules * 2;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
