package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.dto.ContextFileInput;
import java.util.*;
import java.util.regex.Pattern;

/** 在既有安全摘要预算内优先保留相关文件，并避免一个目录占满全部位置。 */
final class PlanningDigestSelector {
    private static final Set<String> DOCUMENT_LANGUAGES = Set.of(
            "text", "md", "markdown", "rst", "asciidoc", "pdf", "doc", "docx", "wps",
            "ppt", "pptx", "dps", "xls", "xlsx", "et", "csv", "tsv", "odt", "ods", "odp"
    );
    private PlanningDigestSelector() { }

    static List<FileSnippet> select(List<FileSnippet> files, String query, int limit) {
        Set<String> terms = terms(query);
        List<FileSnippet> ranked = files.stream().sorted(Comparator
                .comparingInt((FileSnippet file) -> score(file, terms)).reversed()
                .thenComparing(FileSnippet::path)).toList();
        LinkedHashSet<FileSnippet> chosen = new LinkedHashSet<>();
        // 混合上下文中，代码片段即使命中需求更多，也不能挤掉单独上传的业务方案。
        ranked.stream().filter(PlanningDigestSelector::isDocument)
                .limit(Math.min(4, limit)).forEach(chosen::add);
        ranked.stream().limit((limit + 1) / 2).forEach(chosen::add);
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
                || path.matches(".*\\.(?:txt|md|rst|adoc|pdf|docx?|xlsx?|pptx?|csv|tsv|odt|ods|odp)$");
    }

    private static Set<String> terms(String query) {
        Set<String> terms = new HashSet<>();
        String source = query == null ? "" : query;
        var words = Pattern.compile("[a-zA-Z0-9_]{3,}|[\\p{IsHan}]{2,}").matcher(source.toLowerCase(Locale.ROOT));
        while (words.find()) {
            String word = words.group();
            terms.add(word);
            if (word.matches("[\\p{IsHan}]+")) {
                for (int index = 0; index + 2 <= word.length(); index++) terms.add(word.substring(index, index + 2));
            }
        }
        return terms;
    }

    private static int score(FileSnippet file, Set<String> terms) {
        String text = (file.path() + " " + file.summary()).toLowerCase(Locale.ROOT);
        int relevant = (int) terms.stream().filter(text::contains).count();
        return relevant * 10 + (text.matches(".*(readme|pom.xml|package.json|数据字典|研究方案).*") ? 2 : 0);
    }

    private static String directory(String path) {
        String normalized = path.replace('\\', '/');
        int last = normalized.lastIndexOf('/');
        return last < 0 ? "" : normalized.substring(0, last);
    }
}
