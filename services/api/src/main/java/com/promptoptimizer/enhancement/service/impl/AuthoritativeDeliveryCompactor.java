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
 * 具名章节、引用、代码和表格不参与；原定交付物、不同对象与新增条件仍原样保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class AuthoritativeDeliveryCompactor {
    private static final Pattern SENTENCE = Pattern.compile("[^。\\r\\n]+。?");

    private AuthoritativeDeliveryCompactor() { }

    /** 契约已写入权威段落后再归并；只接收服务端本次生成的说明，不使用用户材料作为删除字典。 */
    static void compact(Map<PromptSectionType, PromptSection> sections, Map<PromptSectionType, String> guidance) {
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
            boolean code = false;
            boolean protectedScope = false;
            for (String line : section.content().lines().toList()) {
                String stripped = line.strip();
                if (stripped.startsWith("```")) {
                    code = !code;
                    lines.add(line);
                    continue;
                }
                if (code || stripped.startsWith("|") || stripped.startsWith(">")) {
                    lines.add(line);
                    continue;
                }
                if (stripped.matches("^(?:#{1,6}\\s+.+|\\*\\*.+\\*\\*)$") || stripped.endsWith("：") || stripped.endsWith(":")) {
                    // 仅平台元信息标题不承载新业务对象；其他章节中的省略主语不能跨范围删除。
                    protectedScope = !metadataHeading(stripped);
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

    /** 固定元信息不改变对象，业务章节一律保守保留，不按行业关键词猜测章节用途。 */
    private static boolean metadataHeading(String heading) {
        String value = heading.replaceFirst("^#{1,6}\\s+", "").replaceAll("[*：:]", "");
        return value.equals("平台强制约束（不得删除或弱化）") || value.equals("未决决定的交付边界")
                || value.equals("用户明确规则（须遵守平台权限边界）")
                || value.equals("当前参数依据（用于生成指标表，不代替指标表）");
    }

    /** 只归一化平台契约的排版与有限完整句前缀；新条件、对象、数值与例外必须继续逐字匹配。 */
    private static String key(String text) {
        return Normalizer.normalize(text.strip().replaceFirst("^[-*•]\\s+", ""), Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .replaceFirst("^(?:上述)?未决参数在正文", "同一未决决定在正文")
                .replaceFirst("^指标表:逐行包含", "指标表逐行包含");
    }
}
