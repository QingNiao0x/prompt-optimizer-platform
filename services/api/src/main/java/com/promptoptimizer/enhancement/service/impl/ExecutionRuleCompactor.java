package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/**
 * 去掉执行段落中逐字等值的完整行或独立句重复；不靠主题或行业相似度删除规则。
 * 背景、溯源和待确认清单保持独立，数值、运算符、条件及代码大小写参与比较。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ExecutionRuleCompactor {
    private static final java.util.regex.Pattern FENCE = java.util.regex.Pattern.compile("^(`{3,}|~{3,})(.*)$");
    private ExecutionRuleCompactor() { }

    /** 只在另一执行段已完整保留同一规则时删除重复行；四要素不能为空。 */
    static void compact(Map<PromptSectionType, PromptSection> sections) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenSentences = new LinkedHashSet<>();
        for (PromptSectionType type : java.util.List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT,
                PromptSectionType.CONSTRAINTS, PromptSectionType.ACCEPTANCE)) {
            PromptSection section = sections.get(type);
            if (section == null) continue;
            Set<String> pending = new LinkedHashSet<>();
            Set<String> pendingSentences = new LinkedHashSet<>();
            var retained = new ArrayList<String>();
            String scope = "";
            String fence = null;
            for (String line : section.content().lines().toList()) {
                // 重复代码可能属于不同分支；表格行靠列和相邻行定位，不能当作独立规则删去。
                var marker = FENCE.matcher(line.strip());
                if (marker.matches()) {
                    String token = marker.group(1);
                    if (fence == null) fence = token;
                    else if (token.charAt(0) == fence.charAt(0) && token.length() >= fence.length()
                            && marker.group(2).isBlank()) fence = null;
                    retained.add(line);
                    continue;
                }
                if (fence != null || line.strip().startsWith("|") || line.strip().startsWith(">")) {
                    retained.add(line);
                    continue;
                }
                String value = key(line);
                // 章节和冒号标题承载隐含业务对象；同一句话放在审批／退款下不能跨范围合并。
                if (line.strip().matches("^(?:#{1,6}\\s+.+|\\*\\*.+\\*\\*)$") || value.endsWith("：") || value.endsWith(":")) {
                    // 平台补回的同一份用户规则不产生新业务对象；真实业务章节继续单独保留。
                    scope = value.equals("用户明确规则(不得反转或遗漏):")
                            || value.equals("用户明确规则(须遵守平台权限边界):") ? "" : value;
                    retained.add(line);
                    continue;
                }
                line = compactRepeatedSentences(line, scope, seenSentences, pendingSentences);
                if (line.isBlank()) continue;
                value = key(line);
                String scoped = scope + '\u0000' + value;
                if (value.length() < 12 || !seen.contains(scoped) && !pending.contains(scoped)) {
                    retained.add(line);
                    if (value.length() >= 12) pending.add(scoped);
                }
            }
            String cleaned = String.join("\n", retained).strip();
            // 完全重复的必需段落仍保留，不用占位句冒充有效四要素。
            if (!cleaned.isBlank()) {
                sections.put(type, new PromptSection(type, section.title(), cleaned));
                seen.addAll(pending);
                seenSentences.addAll(pendingSentences);
            }
        }
    }

    /**
     * 只移除同一范围内的完整句子复写，不拆逗号或分号连接的条件、例外和指代。
     * 代码、表格、引文在调用前已排除；长句中的非重复信息不改写、不概括。
     */
    private static String compactRepeatedSentences(String line, String scope,
            Set<String> seen, Set<String> pending) {
        var sentences = java.util.regex.Pattern.compile("[^。\\r\\n]+。?").matcher(line);
        Set<String> retained = new LinkedHashSet<>();
        StringBuilder result = new StringBuilder();
        while (sentences.find()) {
            String sentence = sentences.group();
            String identity = key(sentence).replaceAll("[。]+$", "");
            // 指代、转折或条件句依赖上一句的对象；即便文字一样，也不能独立搬离其范围。
            boolean dependent = identity.matches("^(?:其中|其|该|此|上述|以下|否则|但|若|如果|假如|当|仅当|只有).*" );
            String scoped = scope + '\u0000' + identity;
            boolean complete = sentence.endsWith("。");
            if (identity.length() < 12 || dependent || (!complete || !seen.contains(scoped)
                    && !pending.contains(scoped)) && retained.add(identity)) {
                result.append(sentence);
                if (complete && identity.length() >= 12 && !dependent) pending.add(scoped);
            }
        }
        return result.toString();
    }

    private static String key(String value) {
        return Normalizer.normalize(value.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", ""), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ");
    }
}
