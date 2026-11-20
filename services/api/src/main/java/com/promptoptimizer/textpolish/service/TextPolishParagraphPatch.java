package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 只替换明确登记的段落，在服务端复用未修改的原文和原始分隔符。 */
final class TextPolishParagraphPatch {
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("(?>\\R)[\\p{Zs}\\t]*(?>\\R)");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private final String source;
    private final List<Slice> slices;
    private final Map<String, Slice> byId;

    TextPolishParagraphPatch(String source) {
        this.source = source;
        var indexed = new LinkedHashMap<String, Slice>();
        int start = 0;
        var boundaries = PARAGRAPH_BREAK.matcher(source);
        while (boundaries.find()) {
            add(indexed, start, boundaries.start());
            start = boundaries.end();
        }
        add(indexed, start, source.length());
        this.slices = List.copyOf(indexed.values());
        this.byId = Map.copyOf(indexed);
    }

    /** 模型持有全文和这份段号索引。只给出核心文本，段落外侧空白由服务端复用。 */
    List<Map<String, String>> paragraphs() {
        return slices.stream().map(slice -> Map.of("id", slice.id(), "text", slice.text())).toList();
    }

    /** 拒绝未知段号、重复替换和换行结构变化；编辑列表为空时精确保留原文。 */
    String apply(JsonNode response) {
        if (response == null || !response.isObject() || response.size() != 1 || !response.path("edits").isArray())
            throw invalid();
        var replacements = new LinkedHashMap<String, String>();
        for (JsonNode edit : response.path("edits")) {
            if (!edit.isObject() || edit.size() != 2 || !edit.path("id").isTextual() || !edit.path("text").isTextual())
                throw invalid();
            String id = edit.path("id").textValue();
            String text = edit.path("text").textValue();
            Slice slice = byId.get(id);
            if (slice == null || text.isBlank() || !text.equals(text.strip())
                    || PARAGRAPH_BREAK.matcher(text).find()
                    || !lineBreaks(text).equals(lineBreaks(slice.text())) || replacements.putIfAbsent(id, text) != null)
                throw invalid();
        }
        if (replacements.isEmpty()) return source;
        var result = new StringBuilder(source.length());
        int cursor = 0;
        for (Slice slice : slices) {
            String replacement = replacements.get(slice.id());
            if (replacement == null) continue;
            result.append(source, cursor, slice.start()).append(replacement);
            cursor = slice.end();
        }
        return result.append(source, cursor, source.length()).toString();
    }

    /** 记录段落实质内容的 UTF-16 边界，保留原来的缩进、结尾空白和换行。 */
    private void add(Map<String, Slice> indexed, int start, int end) {
        String raw = source.substring(start, end);
        if (raw.isBlank()) return;
        String leftTrimmed = raw.stripLeading();
        String text = leftTrimmed.stripTrailing();
        int offset = start + raw.length() - leftTrimmed.length();
        String id = "P" + (indexed.size() + 1);
        indexed.put(id, new Slice(id, offset, offset + text.length(), text));
    }

    private static List<String> lineBreaks(String text) {
        return LINE_BREAK.matcher(text).results().map(match -> match.group()).toList();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("段落替换协议无效"); }

    private record Slice(String id, int start, int end, String text) { }
}
