package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将本次平台交付契约的完整等价句保留在指定段落一次，不以主题相似度删除业务规则。
 * 具名章节、引用、代码和业务表格不参与；仅完整相同的本次平台参数表可在权威段落归并。
 * 原定交付物、不同对象与新增条件仍原样保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class AuthoritativeDeliveryCompactor {
    private static final Pattern SENTENCE = Pattern.compile("[^。\\r\\n]+。?");
    private static final String PARAMETER_HEADING = "当前参数依据（用于生成指标表，不代替指标表）：";
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(.*)$");

    private AuthoritativeDeliveryCompactor() { }

    /** 契约已写入权威段落后再归并；只接收服务端本次生成的说明，不使用用户材料作为删除字典。 */
    static void compact(Map<PromptSectionType, PromptSection> sections, Map<PromptSectionType, String> guidance) {
        guidance.forEach((type, text) -> {
            PromptSection section = sections.get(type);
            if (section == null) return;
            Set<String> tables = currentParameterTables(text);
            if (tables.isEmpty()) return;
            sections.put(type, new PromptSection(type, section.title(), compactParameterTables(section.content(), tables)));
        });
        Map<String, PromptSectionType> authority = new LinkedHashMap<>();
        guidance.forEach((type, text) -> text.lines().filter(line -> !line.strip().startsWith("|"))
                .flatMap(line -> SENTENCE.matcher(line).results()).map(match -> match.group())
                .filter(sentence -> sentence.endsWith("。"))
                .forEach(sentence -> authority.putIfAbsent(key(sentence), type)));
        if (authority.isEmpty()) return;
        for (PromptSectionType type : java.util.List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT,
                PromptSectionType.CONSTRAINTS, PromptSectionType.ACCEPTANCE)) {
            PromptSection section = sections.get(type);
            if (section == null) continue;
            Set<String> retainedAuthority = new LinkedHashSet<>();
            var lines = new ArrayList<String>();
            char fenceType = 0;
            int fenceLength = 0;
            boolean protectedScope = false;
            for (String line : section.content().lines().toList()) {
                String stripped = line.strip();
                var fence = FENCE.matcher(stripped);
                if (fence.matches()) {
                    String marker = fence.group(1);
                    if (fenceType == 0) {
                        fenceType = marker.charAt(0);
                        fenceLength = marker.length();
                    } else if (marker.charAt(0) == fenceType && marker.length() >= fenceLength && fence.group(2).isBlank()) {
                        fenceType = 0;
                    }
                    lines.add(line);
                    continue;
                }
                if (fenceType != 0 || stripped.startsWith("|") || stripped.startsWith(">")) {
                    lines.add(line);
                    continue;
                }
                if (stripped.matches("^(?:#{1,6}\\s+.+|\\*\\*.+\\*\\*)$") || stripped.endsWith("：") || stripped.endsWith(":")) {
                    // 仅平台元信息标题不承载新业务对象；其他章节中的省略主语不能跨范围删除。
                    // 普通交付标题不建立新对象，也不能解除之前的具名业务章节保护。
                    protectedScope = protectedScope || !metadataHeading(stripped);
                    lines.add(line);
                    continue;
                }
                StringBuilder kept = new StringBuilder();
                var matches = SENTENCE.matcher(line);
                while (matches.find()) {
                    String sentence = matches.group();
                    String identity = key(sentence);
                    PromptSectionType owner = authority.get(identity);
                    if (protectedScope || owner == null || !sentence.endsWith("。")
                            || owner == type && retainedAuthority.add(identity)) kept.append(sentence);
                }
                if (!kept.toString().isBlank()) lines.add(kept.toString());
            }
            String compacted = String.join("\n", lines).strip();
            // 无法精简为空的必需段落，继续保留原文；不生成占位句假装交付完整。
            if (!compacted.isBlank()) sections.put(type, new PromptSection(type, section.title(), compacted));
        }
    }

    /** 完整标题、表头及所有行一起登记；额外一行、不同状态或另一对象都不属于同一权威表。 */
    private static Set<String> currentParameterTables(String text) {
        var lines = text.lines().toList();
        Set<String> tables = new LinkedHashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).strip().equals(PARAMETER_HEADING)) continue;
            int end = parameterTableEnd(lines, index);
            if (end - index < 4) continue;
            tables.add(parameterTableKey(lines.subList(index, end)));
        }
        return tables;
    }

    /** 只消费标题紧接的连续表格，不能截取一个长表的前半段当作相同表。 */
    private static int parameterTableEnd(java.util.List<String> lines, int start) {
        int end = start + 1;
        while (end < lines.size() && lines.get(end).strip().startsWith("|")) end++;
        return end;
    }

    /** 仅统一行首尾排版，单元格内部的主体、条件、空格与状态逐字比较。 */
    private static String parameterTableKey(java.util.List<String> lines) {
        return lines.stream().map(String::strip).collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * 在当前权威段落保留一个完整副本；业务章节、引用及同类代码围栏内的原件不参与。
     * 元信息标题不解除业务章节保护，不用相似表头或相同属性删除其他交付表。
     */
    private static String compactParameterTables(String text, Set<String> tables) {
        var original = text.lines().toList();
        var result = new ArrayList<String>();
        Set<String> retained = new LinkedHashSet<>();
        char fenceType = 0;
        int fenceLength = 0;
        boolean businessScope = false;
        for (int index = 0; index < original.size(); index++) {
            String line = original.get(index);
            String stripped = line.strip();
            var fence = FENCE.matcher(stripped);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (fenceType == 0) {
                    fenceType = marker.charAt(0);
                    fenceLength = marker.length();
                } else if (marker.charAt(0) == fenceType && marker.length() >= fenceLength && fence.group(2).isBlank()) {
                    fenceType = 0;
                }
                result.add(line);
                continue;
            }
            if (fenceType != 0 || stripped.startsWith(">")) {
                result.add(line);
                continue;
            }
            if ((stripped.matches("^(?:#{1,6}\\s+.+|\\*\\*.+\\*\\*)$") || stripped.endsWith("：") || stripped.endsWith(":"))
                    && !metadataHeading(stripped)) businessScope = true;
            if (!businessScope && stripped.equals(PARAMETER_HEADING)) {
                int end = parameterTableEnd(original, index);
                String identity = parameterTableKey(original.subList(index, end));
                if (tables.contains(identity)) {
                    if (retained.add(identity)) result.addAll(original.subList(index, end));
                    index = end - 1;
                    continue;
                }
            }
            result.add(line);
        }
        return String.join("\n", result);
    }

    /** 固定元信息不改变对象，业务章节一律保守保留，不按行业关键词猜测章节用途。 */
    private static boolean metadataHeading(String heading) {
        String value = heading.replaceFirst("^#{1,6}\\s+", "").replaceAll("[*：:]", "");
        return value.equals("平台强制约束（不得删除或弱化）") || value.equals("未决决定的交付边界")
                || value.equals("用户明确规则（须遵守平台权限边界）")
                || value.equals("当前参数依据（用于生成指标表，不代替指标表）")
                || value.equals("交付物") || value.equals("交付要求") || value.equals("输出要求")
                || value.equals("实体标识的独立依据（A/B集合不证明名称对应，不按次序或排除法绑定）");
    }

    /** 只归一化平台契约的排版与有限完整句前缀；新条件、对象、数值与例外必须继续逐字匹配。 */
    private static String key(String text) {
        return Normalizer.normalize(text.strip().replaceFirst("^[-*•]\\s+", ""), Normalizer.Form.NFKC)
                // 已登记平台句里的代码名只作行内排版；代码块、实际业务表与数值字面量不在此处理。
                .replaceAll("`([A-Za-z_][A-Za-z0-9_. ]*)`", "$1")
                .replaceAll("\\s+", "")
                .replaceFirst("^(?:上述)?未决参数在正文", "同一未决决定在正文")
                .replaceFirst("^指标表:逐行包含", "指标表逐行包含");
    }
}
