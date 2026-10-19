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

    /** 旧调用方保持原非空兼容行为；仅显式结构化组装启用可空辅助槽。 */
    static void compact(Map<PromptSectionType, PromptSection> sections) {
        compact(sections, Set.of());
    }

    /** 完整输出来源只作保护范围，不作删除字典；常规去重不能拆碎权威块后再触发补回。 */
    static void compact(Map<PromptSectionType, PromptSection> sections, Set<String> outputSources) {
        compact(sections, outputSources, false);
    }

    /** 仅在完整规则已由另一段承担时清空辅助槽；TASK、独立条件及受保护输出来源不清空。 */
    static void compact(Map<PromptSectionType, PromptSection> sections, Set<String> outputSources,
            boolean allowEmptyAuxiliary) {
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
            int sourceOffset = 0;
            for (String line : section.content().lines().toList()) {
                int lineOffset = section.content().indexOf(line, sourceOffset);
                sourceOffset = lineOffset + line.length();
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
                if (fence != null || line.strip().startsWith("|") || line.strip().startsWith(">")
                        || line.startsWith("    ") || line.startsWith("\t")
                        || line.matches(".*[\"“”‘’].*") || dependentLine(key(line))) {
                    retained.add(line);
                    continue;
                }
                String value = key(line);
                // 章节和冒号标题承载隐含业务对象；同一句话放在审批／退款下不能跨范围合并。
                if (line.strip().matches("^(?:#{1,6}\\s+.+|\\*\\*.+\\*\\*)$") || value.endsWith("：") || value.endsWith(":")) {
                    // 固定投影标题不建立新对象，也不能解除已经进入的支付／查询等业务范围。
                    if (!projectionHeading(value)) scope = value;
                    retained.add(line);
                    continue;
                }
                if (type == PromptSectionType.OUTPUT && protectedOutputLine(section.content(), outputSources, lineOffset, line.length())) {
                    retained.add(line);
                    continue;
                }
                line = compactRepeatedSentences(line, scope, seenSentences, pendingSentences);
                if (line.isBlank()) continue;
                value = key(line);
                String scoped = scope + '\u0000' + value;
                if (!independentRule(value) || !seen.contains(scoped) && !pending.contains(scoped)) {
                    retained.add(line);
                    if (independentRule(value)) pending.add(scoped);
                }
            }
            String cleaned = String.join("\n", removeEmptyProjectionHeadings(retained)).strip();
            // 新结构化协议允许辅助槽为空；保留槽位供最终保真和安全补全，不恢复重复原段。
            if (!cleaned.isBlank()) {
                sections.put(type, new PromptSection(type, section.title(), cleaned));
                seen.addAll(pending);
                seenSentences.addAll(pendingSentences);
            } else if (type == PromptSectionType.ACCEPTANCE) {
                sections.remove(type);
            } else if (allowEmptyAuxiliary && type.allowsEmptyContent()) {
                sections.put(type, new PromptSection(type, section.title(), ""));
            }
        }
    }

    /** 原句必须完整存在才保护其相交行；保守保留整行，绝不裁剪对象、标识符或相邻条件。 */
    private static boolean protectedOutputLine(String content, Set<String> sources, int lineStart, int lineLength) {
        for (String source : sources) {
            if (source.isBlank()) continue;
            int start = content.indexOf(source);
            while (start >= 0) {
                if (lineStart < start + source.length() && lineStart + lineLength > start) return true;
                start = content.indexOf(source, start + source.length());
            }
        }
        return false;
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
            if (!independentRule(identity) || dependent || (!complete || !seen.contains(scoped)
                    && !pending.contains(scoped)) && retained.add(identity)) {
                result.append(sentence);
                if (complete && independentRule(identity) && !dependent) pending.add(scoped);
            }
        }
        return result.toString();
    }

    /** 短句必须带完整动作和对象；不再因字符少而重复“不使用第三方依赖”等完整要求。 */
    private static boolean independentRule(String text) {
        return text.length() >= 12 || text.replaceFirst("。$", "").matches(
                "^(?:请|必须|应|须|需|务必)?(?:不得|不要|禁止|不|只|仅)?"
                        + "(?:提供|交付|输出|返回|保留|记录|打印|泄露|编造|添加|使用|修改|删除|读取|生成|说明|校验|处理|运行|执行|采用)"
                        + "[^。；;，,]{2,}$");
    }

    /** 只接收本组装器的固定来源标题；具名业务章节和未知标题不参与范围归并。 */
    private static boolean projectionHeading(String value) {
        return java.util.Set.of("Java21交付:", "任务相关约束:",
                "用户明确规则(不得反转或遗漏):", "用户明确规则(须遵守平台权限边界):").contains(value);
    }

    /** 后置转折可限制前一句；整行保留，不把例外前的原句登记成无条件覆盖证据。 */
    private static boolean dependentLine(String value) {
        return value.matches("^(?:其中|其|该|此|上述|以下|否则|但|若|如果|假如|当|仅当|只有|未来|以后|待确认).*" )
                || value.matches(".*。(?:否则|但|然而|不过|除非).*" )
                || value.matches(".*[；;，,](?:但|然而|不过|除非).*" )
                || value.contains("除外") || value.contains("例外") || value.contains("除非");
    }

    /** 去掉已无任何正文的固定投影标题，避免规则去重后留下空架子。 */
    private static java.util.List<String> removeEmptyProjectionHeadings(java.util.List<String> lines) {
        var result = new ArrayList<String>();
        for (int index = 0; index < lines.size(); index++) {
            if (projectionHeading(key(lines.get(index)))) {
                int next = index + 1;
                while (next < lines.size() && lines.get(next).isBlank()) next++;
                if (next == lines.size() || projectionHeading(key(lines.get(next)))
                        || lines.get(next).strip().matches("^(?:#{1,6}\\s+.+|\\*\\*.+\\*\\*)$")
                        || key(lines.get(next)).endsWith(":")) continue;
            }
            result.add(lines.get(index));
        }
        return result;
    }

    private static String key(String value) {
        return Normalizer.normalize(value.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", ""), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ");
    }
}
