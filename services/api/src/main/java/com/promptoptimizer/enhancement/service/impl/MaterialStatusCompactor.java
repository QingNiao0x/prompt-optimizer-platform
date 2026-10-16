package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 将完全受当前约束覆盖的平列资料状态从背景省去；不改来源、未决集合或独立对象。 */
final class MaterialStatusCompactor {
    private static final Pattern STATUS = Pattern.compile("([^。！？!?\\r\\n]+?)在现有资料中未提供(，[^。！？!?\\r\\n]+)?[。.]?");
    private record Status(int start, int end, Set<String> objects, String handling) { }

    private MaterialStatusCompactor() { }

    /** 只比较同状态、同处理义务的完整平列对象；约束是唯一保留位置，背景事实不删。 */
    static void compact(Map<PromptSectionType, PromptSection> sections) {
        var background = sections.get(PromptSectionType.BACKGROUND);
        var constraints = sections.get(PromptSectionType.CONSTRAINTS);
        if (background == null || constraints == null) return;
        List<Status> authority = statuses(constraints.content());
        if (authority.isEmpty()) return;
        var updated = new StringBuilder();
        int cursor = 0;
        for (Status candidate : statuses(background.content())) {
            if (authority.stream().anyMatch(current -> current.objects().containsAll(candidate.objects())
                    && current.handling().equals(candidate.handling()))) {
                // 按原位置删除，不能把引用或另一段里字面相同的来源内容一并替换掉。
                updated.append(background.content(), cursor, candidate.start());
                cursor = candidate.end();
            }
        }
        updated.append(background.content().substring(cursor));
        // 四要素仍保留有用内容；只有状态的背景不制造空段落，也不把状态改写成事实。
        if (!updated.toString().isBlank()) sections.put(background.type(), new PromptSection(background.type(), background.title(), updated.toString().strip()));
    }

    /** 不跨标题、代码、引用或条件收集覆盖证据；对象逐字比对，不以关键词并集替代身份。 */
    private static List<Status> statuses(String content) {
        if (!content.contains("在现有资料中未提供")) return List.of();
        var result = new java.util.ArrayList<Status>();
        boolean protectedBlock = false;
        var lines = Pattern.compile("[^\\r\\n]+").matcher(content);
        while (lines.find()) {
            String line = lines.group();
            String current = line.strip();
            if (current.startsWith("```") || current.startsWith("~~~") || current.startsWith("#")
                    || current.endsWith("：") || current.endsWith(":")) protectedBlock = true;
            if (protectedBlock || current.startsWith(">") || current.startsWith("|")
                    || current.matches(".*(?:如果|假如|未来|以后|仅限|除非|例外|取得授权|另一|其他研究|不是|并非).*")) continue;
            var sentences = Pattern.compile("[^。！？!?]+[。！？!?]?").matcher(line);
            while (sentences.find()) {
                String sentence = sentences.group();
                if (!sentence.contains("在现有资料中未提供")) continue;
                // 每个完整句只匹配一次，避免在长材料无状态片段上反复尝试起点。
                var match = STATUS.matcher(sentence);
                if (!match.matches()) continue;
                String names = match.group(1).strip().replaceFirst("^[-*•]\\s*", "");
                List<String> objects = Arrays.asList(names.split("、", -1));
                if (objects.stream().anyMatch(value -> !value.matches("[\\p{IsHan}A-Za-z0-9/_-]{2,24}"))) continue;
                String handling = match.group(2) == null ? "" : Normalizer.normalize(match.group(2), Normalizer.Form.NFKC)
                        .replaceAll("以([“「][^”」]{1,40}[”」])标注", "标注$1");
                // 范围包含对应列表标记；删除后不会留下单独项目符号。
                result.add(new Status(lines.start() + sentences.start(), lines.start() + sentences.end(), Set.copyOf(objects), handling));
            }
        }
        return List.copyOf(result);
    }
}
