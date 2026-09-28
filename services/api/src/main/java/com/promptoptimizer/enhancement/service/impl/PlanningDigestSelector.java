package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.dto.ContextFileInput;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 在既有安全摘要预算内优先保留相关文件，并避免一个目录占满全部位置。 */
final class PlanningDigestSelector {
    private static final Set<String> DOCUMENT_LANGUAGES = Set.of(
            "text", "md", "markdown", "rst", "asciidoc", "pdf", "doc", "docx", "wps",
            "ppt", "pptx", "dps", "xls", "xlsx", "et", "csv", "tsv", "odt", "ods", "odp"
    );
    private static final Pattern DOCUMENT_EXTENSION = Pattern.compile(
            ".*\\.(?:txt|md|rst|adoc|pdf|docx?|xlsx?|pptx?|csv|tsv|odt|ods|odp)$");
    private static final Pattern SEARCH_WORD = Pattern.compile("[a-zA-Z0-9_]{3,}|[\\p{IsHan}]{2,}");
    private static final Pattern CHINESE_WORD = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern PRIORITY_DESCRIPTION = Pattern.compile(
            ".*(readme|pom.xml|package.json|数据字典|研究方案).*");
    private PlanningDigestSelector() { }

    /** 每份摘要只评分一次，复用排序键；保留相关性、文档保底与目录覆盖的原有选择顺序。 */
    static List<FileSnippet> select(List<FileSnippet> files, String query, int limit) {
        if (limit <= 0 || files == null || files.isEmpty()) return List.of();
        Set<String> terms = terms(query);
        PlanningEvidencePolicy evidencePolicy = new PlanningEvidencePolicy(query);
        List<FileSnippet> ranked = files.stream()
                .filter(evidencePolicy::allows)
                .map(file -> new RankedSnippet(file, score(file, terms)))
                .sorted(Comparator.comparingInt(RankedSnippet::score).reversed()
                        .thenComparing(item -> item.file().path()))
                .map(RankedSnippet::file).toList();
        LinkedHashSet<FileSnippet> chosen = new LinkedHashSet<>();
        // 混合上下文中，代码片段即使命中需求更多，也不能挤掉单独上传的业务方案。
        ranked.stream().filter(PlanningDigestSelector::isDocument)
                .limit(Math.min(4, limit)).forEach(chosen::add);
        ranked.stream().limit((limit + 1) / 2).forEach(file -> {
            if (chosen.size() < limit) chosen.add(file);
        });
        Set<String> directories = new HashSet<>();
        chosen.forEach(file -> directories.add(directory(file.path())));
        for (FileSnippet file : ranked) {
            if (chosen.size() >= limit) break;
            if (directories.add(directory(file.path()))) chosen.add(file);
        }
        for (FileSnippet file : ranked) {
            if (chosen.size() >= limit) break;
            chosen.add(file);
        }
        return List.copyOf(chosen);
    }

    static boolean relevant(FileSnippet file, String query) {
        return score(file, terms(query)) >= 10;
    }

    static boolean isDocument(FileSnippet file) {
        return isDocument(file.path(), file.language());
    }

    static boolean isDocument(ContextFileInput file) {
        return isDocument(file.path(), file.language());
    }

    private static boolean isDocument(String rawPath, String rawLanguage) {
        String language = rawLanguage == null ? "" : rawLanguage.toLowerCase(Locale.ROOT);
        String path = rawPath == null ? "" : rawPath.toLowerCase(Locale.ROOT);
        return DOCUMENT_LANGUAGES.contains(language)
                || DOCUMENT_EXTENSION.matcher(path).matches();
    }

    private static Set<String> terms(String query) {
        Set<String> terms = new HashSet<>();
        String source = query == null ? "" : query;
        var words = SEARCH_WORD.matcher(source.toLowerCase(Locale.ROOT));
        while (words.find()) {
            String word = words.group();
            terms.add(word);
            if (CHINESE_WORD.matcher(word).matches()) {
                for (int index = 0; index + 2 <= word.length(); index++) terms.add(word.substring(index, index + 2));
            }
        }
        return terms;
    }

    private static int score(FileSnippet file, Set<String> terms) {
        String text = (file.path() + " " + file.summary()).toLowerCase(Locale.ROOT);
        int relevant = (int) terms.stream().filter(text::contains).count();
        return relevant * 10 + (PRIORITY_DESCRIPTION.matcher(text).matches() ? 2 : 0);
    }

    private static String directory(String path) {
        String normalized = path.replace('\\', '/');
        int last = normalized.lastIndexOf('/');
        return last < 0 ? "" : normalized.substring(0, last);
    }

    /** 请求内的排序键，不缓存用户摘要或跨请求共享业务材料。 */
    private record RankedSnippet(FileSnippet file, int score) { }
}
