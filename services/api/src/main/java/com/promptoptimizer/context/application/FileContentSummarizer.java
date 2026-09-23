package com.promptoptimizer.context.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 * @Description: 为代码、普通文档和图片元数据生成可直接展示的简短摘要。
 */
@Component
public class FileContentSummarizer {

    private static final int MAX_SUMMARY_CHARACTERS = 600;
    private static final int MAX_DOCUMENT_SEGMENTS = 5;
    private static final int MAX_SYMBOLS = 8;
    private static final Pattern DECLARATION_PATTERN = Pattern.compile(
            "\\b(?:class|interface|enum|record|struct|trait|function|def|fn|const|let|var)\\s+([A-Za-z_$][\\w$]*)"
    );
    private static final Set<String> CODE_LANGUAGES = Set.of(
            "c", "cpp", "csharp", "dart", "elixir", "fsharp", "go", "java", "javascript",
            "kotlin", "lua", "php", "python", "ruby", "rust", "scala", "shell", "swift",
            "typescript", "vue"
    );
    private static final Set<String> IMAGE_LANGUAGES = Set.of(
            "bmp", "gif", "jpeg", "jpg", "png", "webp"
    );

    /**
     * 根据文件类型选择摘要策略。摘要只使用已经完成安全检查的内容，不读取其他路径。
     */
    public String summarize(String path, String language, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        if (content.startsWith("[CONTENT REDACTED:")) {
            return "文件内容疑似包含敏感信息，正文已脱敏，未生成内容摘要。";
        }

        String normalizedLanguage = language == null || language.isBlank()
                ? "text"
                : language.toLowerCase(Locale.ROOT);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (IMAGE_LANGUAGES.contains(normalizedLanguage)) {
            return limit("图片元数据：" + joinSegments(content, MAX_DOCUMENT_SEGMENTS));
        }
        if (lowerPath.endsWith("pom.xml")) {
            return limit("Maven 项目配置。" + summarizeDocument(content));
        }
        if (lowerPath.endsWith("package.json")) {
            return limit("Node.js 项目清单。" + summarizeDocument(content));
        }
        if (CODE_LANGUAGES.contains(normalizedLanguage)) {
            return summarizeCode(normalizedLanguage, content);
        }
        return limit(fileTypeLabel(normalizedLanguage) + "摘要：" + summarizeDocument(content));
    }

    /** 提取有限数量的代码符号与 Vue 区块，再拼接可阅读的关键内容摘要。 */
    private String summarizeCode(String language, String content) {
        Set<String> symbols = new LinkedHashSet<>();
        Matcher matcher = DECLARATION_PATTERN.matcher(content);
        while (matcher.find() && symbols.size() < MAX_SYMBOLS) {
            symbols.add(matcher.group(1));
        }

        StringBuilder result = new StringBuilder(codeLanguageLabel(language)).append("代码文件");
        if (!symbols.isEmpty()) {
            result.append("，主要定义：").append(String.join("、", symbols));
        }
        if ("vue".equals(language)) {
            List<String> sections = new ArrayList<>();
            if (content.contains("<template")) sections.add("template");
            if (content.contains("<script")) sections.add("script");
            if (content.contains("<style")) sections.add("style");
            if (!sections.isEmpty()) {
                result.append("；包含 ").append(String.join("、", sections)).append(" 区块");
            }
        }
        result.append("。关键内容：").append(joinSegments(content, 2));
        return limit(result.toString());
    }

    private String summarizeDocument(String content) {
        String summary = joinDistributedSegments(content, MAX_DOCUMENT_SEGMENTS);
        return summary.isBlank() ? "未提取到可概括的文本内容。" : summary;
    }

    /**
     * 从全文的开头、四分之一、中间、四分之三和结尾抽取代表语句。
     * 这里刻意不只读取前几段，否则长报告的结论和验收条件会长期缺失。
     */
    private String joinDistributedSegments(String content, int maxSegments) {
        String normalized = content
                .replace('\r', '\n')
                .replaceAll("\\n+", "\n")
                .trim();
        String[] candidates = normalized.split("\\n|(?<=[。！？!?])|(?<=[.!?])\\s+");
        List<String> cleaned = new ArrayList<>();
        for (String candidate : candidates) {
            String segment = cleanSegment(candidate);
            if (!segment.isBlank()) {
                cleaned.add(segment);
            }
        }
        if (cleaned.isEmpty()) {
            return "";
        }

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        int sampleCount = Math.min(maxSegments, cleaned.size());
        if (sampleCount == 1) {
            selected.add(cleaned.get(0));
        } else {
            for (int position = 0; position < sampleCount; position++) {
                int index = Math.round((float) position * (cleaned.size() - 1) / (sampleCount - 1));
                selected.add(cleaned.get(index));
            }
        }
        return String.join("；", selected);
    }

    /** 从文本开头选择不重复的语句，并同时限制片段数与摘要字符数。 */
    private String joinSegments(String content, int maxSegments) {
        String normalized = content
                .replace('\r', '\n')
                .replaceAll("\\n+", "\n")
                .trim();
        String[] candidates = normalized.split("\\n|(?<=[。！？!?])|(?<=[.!?])\\s+");
        List<String> selected = new ArrayList<>();
        int characters = 0;
        for (String candidate : candidates) {
            String segment = cleanSegment(candidate);
            if (segment.isBlank() || selected.contains(segment)) {
                continue;
            }
            selected.add(segment);
            characters += segment.length();
            if (selected.size() >= maxSegments || characters >= MAX_SUMMARY_CHARACTERS) {
                break;
            }
        }
        return String.join("；", selected);
    }

    private String cleanSegment(String candidate) {
        return candidate
                .replaceFirst("^#{1,6}\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String limit(String value) {
        if (value.length() <= MAX_SUMMARY_CHARACTERS) {
            return value;
        }
        return value.substring(0, MAX_SUMMARY_CHARACTERS - 1).stripTrailing() + "…";
    }

    private String fileTypeLabel(String language) {
        return switch (language) {
            case "doc", "docx", "wps", "odt" -> "文档内容";
            case "ppt", "pptx", "dps", "odp" -> "演示文稿";
            case "xls", "xlsx", "et", "ods", "spreadsheet" -> "表格内容";
            case "pdf" -> "PDF 文档";
            case "markdown" -> "Markdown 文档";
            default -> "文件内容";
        };
    }

    private String codeLanguageLabel(String language) {
        return switch (language) {
            case "csharp" -> "C#";
            case "cpp" -> "C++";
            case "javascript" -> "JavaScript";
            case "typescript" -> "TypeScript";
            case "vue" -> "Vue";
            default -> language.substring(0, 1).toUpperCase(Locale.ROOT) + language.substring(1);
        };
    }
}
