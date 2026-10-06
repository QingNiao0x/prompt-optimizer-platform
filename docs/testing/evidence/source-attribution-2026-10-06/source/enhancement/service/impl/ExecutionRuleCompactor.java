package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/**
 * 去掉执行段落中逐字等值的整行重复；不靠主题或行业相似度删除规则。
 * 背景、溯源和待确认清单保持独立，数值、运算符、条件及代码大小写参与比较。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ExecutionRuleCompactor {
    private ExecutionRuleCompactor() { }

    /** 只在另一执行段已完整保留同一规则时删除重复行；四要素不能为空。 */
    static void compact(Map<PromptSectionType, PromptSection> sections) {
        Set<String> seen = new LinkedHashSet<>();
        for (PromptSectionType type : java.util.List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT,
                PromptSectionType.CONSTRAINTS, PromptSectionType.ACCEPTANCE)) {
            PromptSection section = sections.get(type);
            if (section == null) continue;
            Set<String> pending = new LinkedHashSet<>();
            var retained = new ArrayList<String>();
            String scope = "";
            boolean code = false;
            for (String line : section.content().lines().toList()) {
                // 重复代码可能属于不同分支；表格行靠列和相邻行定位，不能当作独立规则删去。
                if (line.strip().startsWith("```")) {
                    code = !code;
                    retained.add(line);
                    continue;
                }
                if (code || line.strip().startsWith("|") || line.strip().startsWith(">")) {
                    retained.add(line);
                    continue;
                }
                String value = key(line);
                // 章节和冒号标题承载隐含业务对象；同一句话放在审批／退款下不能跨范围合并。
                if (line.strip().matches("^(?:#{1,6}\\s+.+|\\*\\*.+\\*\\*)$") || value.endsWith("：") || value.endsWith(":")) {
                    scope = value;
                    retained.add(line);
                    continue;
                }
                String scoped = scope + '\u0000' + value;
                if (value.length() < 12 || !seen.contains(scoped) && !pending.contains(scoped)) {
                    retained.add(line);
                    if (value.length() >= 12) pending.add(scoped);
                }
            }
            String cleaned = String.join("\n", retained).strip();
            // 完全重复的必需段落仍保留，不用占位句冒充有效四要素。
            if (!cleaned.isBlank()) sections.put(type, new PromptSection(type, section.title(), cleaned));
            seen.addAll(pending);
        }
    }

    private static String key(String value) {
        return Normalizer.normalize(value.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", ""), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ");
    }
}
