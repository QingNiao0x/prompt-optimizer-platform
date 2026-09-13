package com.promptoptimizer.context.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 将长文本切分为可检索片段，并在模型上下文预算内选择任务相关及首中尾代表片段。
 */
public class ContentChunkSelector {

    private static final int CHUNK_CHARACTERS = 6_000;
    private static final int CHUNK_OVERLAP_CHARACTERS = 400;
    private static final int MAX_QUERY_TERMS = 40;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}_.$-]{2,}");
    private static final Pattern HAN_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    /**
     * 根据查询词选择相关片段，并始终保留首、中、尾代表片段，避免概要只反映文件开头。
     */
    public Selection select(String content, String query, int maxCharacters, int maxChunks) {
        if (content == null || content.isBlank() || maxCharacters <= 0 || maxChunks <= 0) {
            return new Selection("", 0, 0, 0, false);
        }

        List<TextChunk> chunks = split(content);
        Set<String> queryTerms = extractSearchTerms(query);
        List<ScoredChunk> scoredChunks = chunks.stream()
                .map(chunk -> new ScoredChunk(chunk, score(chunk.content(), queryTerms)))
                .sorted(Comparator.comparingInt(ScoredChunk::score).reversed()
                        .thenComparingInt(item -> item.chunk().index()))
                .toList();

        LinkedHashSet<Integer> selectedIndexes = new LinkedHashSet<>();
        // 先固定首、中、尾代表片段，再用任务相关片段填满剩余预算。
        // 否则高频关键词可能占满全部名额，让结论或验收内容再次丢失。
        addRepresentativeIndexes(selectedIndexes, chunks.size(), Math.min(3, maxChunks));
        for (ScoredChunk candidate : scoredChunks) {
            if (candidate.score() <= 0 || selectedIndexes.size() >= maxChunks) {
                break;
            }
            selectedIndexes.add(candidate.chunk().index());
        }
        // 查询没有命中或命中数量不足时，从全文均匀补位，避免泛化任务只读取开头。
        addRepresentativeIndexes(selectedIndexes, chunks.size(), maxChunks);
        for (ScoredChunk candidate : scoredChunks) {
            if (selectedIndexes.size() >= maxChunks) {
                break;
            }
            selectedIndexes.add(candidate.chunk().index());
        }

        List<Integer> orderedIndexes = selectedIndexes.stream().sorted().toList();
        StringBuilder selectedContent = new StringBuilder();
        int selectedCharacters = 0;
        int includedChunks = 0;
        for (Integer index : orderedIndexes) {
            TextChunk chunk = chunks.get(index);
            int remaining = maxCharacters - selectedCharacters;
            if (remaining <= 0) {
                break;
            }
            String value = chunk.content().length() > remaining
                    ? chunk.content().substring(0, remaining)
                    : chunk.content();
            if (selectedContent.length() > 0) {
                selectedContent.append("\n\n");
            }
            selectedContent.append("[片段 ")
                    .append(index + 1)
                    .append('/')
                    .append(chunks.size())
                    .append("]\n")
                    .append(value);
            selectedCharacters += value.length();
            includedChunks++;
        }

        return new Selection(
                selectedContent.toString(),
                content.length(),
                selectedCharacters,
                chunks.size(),
                includedChunks < chunks.size() || selectedCharacters < content.length()
        );
    }

    /**
     * 返回均匀覆盖全文的代表样本，供规则摘要使用。
     */
    public List<String> representativeSamples(String content, int sampleCount) {
        if (content == null || content.isBlank() || sampleCount <= 0) {
            return List.of();
        }
        List<TextChunk> chunks = split(content);
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        addRepresentativeIndexes(indexes, chunks.size(), sampleCount);
        return indexes.stream().sorted().map(index -> chunks.get(index).content()).toList();
    }

    private List<TextChunk> split(String content) {
        List<TextChunk> chunks = new ArrayList<>();
        int step = CHUNK_CHARACTERS - CHUNK_OVERLAP_CHARACTERS;
        for (int start = 0, index = 0; start < content.length(); start += step, index++) {
            int end = Math.min(content.length(), start + CHUNK_CHARACTERS);
            chunks.add(new TextChunk(index, content.substring(start, end)));
            if (end == content.length()) {
                break;
            }
        }
        return chunks;
    }

    private void addRepresentativeIndexes(Set<Integer> indexes, int chunkCount, int limit) {
        if (chunkCount <= 0 || limit <= 0) {
            return;
        }
        int representativeCount = Math.min(limit, chunkCount);
        if (representativeCount == 1) {
            indexes.add(0);
            return;
        }
        for (int position = 0; position < representativeCount && indexes.size() < limit; position++) {
            int index = Math.round((float) position * (chunkCount - 1) / (representativeCount - 1));
            indexes.add(index);
        }
    }

    int score(String content, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) {
            return 0;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : queryTerms) {
            if (normalized.contains(term)) {
                score += Math.min(20, 2 + term.length());
            }
        }
        return score;
    }

    Set<String> extractSearchTerms(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = WORD_PATTERN.matcher(normalized);
        while (matcher.find() && terms.size() < MAX_QUERY_TERMS) {
            terms.add(matcher.group());
        }
        Matcher hanMatcher = HAN_PATTERN.matcher(normalized);
        while (hanMatcher.find() && terms.size() < MAX_QUERY_TERMS) {
            String sequence = hanMatcher.group();
            for (int gramSize = 4; gramSize >= 2 && terms.size() < MAX_QUERY_TERMS; gramSize--) {
                for (int index = 0; index + gramSize <= sequence.length()
                        && terms.size() < MAX_QUERY_TERMS; index++) {
                    terms.add(sequence.substring(index, index + gramSize));
                }
            }
        }
        return terms;
    }

    /**
     * 长文本选择结果。totalCharacters 表示完整提取量，selectedCharacters 表示本次快照实际携带量。
     */
    public record Selection(
            String content,
            int totalCharacters,
            int selectedCharacters,
            int totalChunks,
            boolean contextLimited
    ) {
    }

    private record TextChunk(int index, String content) {
    }

    private record ScoredChunk(TextChunk chunk, int score) {
    }
}
