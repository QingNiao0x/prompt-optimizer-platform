package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.Map;
import java.util.regex.Pattern;

/** 澄清模型条件句的前提，不用模型举例证明用户已经要求了某张表。 */
final class ConditionalTableProjection {
    private static final Pattern CONDITIONAL_TABLE = Pattern.compile(
            "用户明确要求的表格(?:[（(](?:如|例如|比如)[^（）()\\r\\n]{1,100}[）)])?(?=在正文中保留表结构|保留表结构)");

    private ConditionalTableProjection() { }

    /** 只展开现有条件句，不增加表格禁令、不删除真正的表格义务，也不改写引用或代码。 */
    static void reconcile(Map<PromptSectionType, PromptSection> sections) {
        for (PromptSectionType type : java.util.List.of(PromptSectionType.OUTPUT, PromptSectionType.ACCEPTANCE)) {
            var section = sections.get(type);
            if (section == null) continue;
            boolean fenced = false;
            boolean foreign = false;
            var lines = new java.util.ArrayList<String>();
            for (String line : section.content().lines().toList()) {
                String value = line.strip();
                boolean marker = value.startsWith("```") || value.startsWith("~~~");
                if (marker) fenced = !fenced;
                String heading = value.replaceFirst("^#{1,6}\\s*", "").replace("**", "");
                if (!fenced && !marker && !value.startsWith(">") && !value.startsWith("|")) {
                    if (heading.matches("(?:另一(?:项)?研究|其他研究|甲研究|乙研究|未来方案|以后确认)[：:]?")) foreign = true;
                    else if (heading.matches("(?:本次研究|本研究|本任务)[：:]?")) foreign = false;
                }
                lines.add(fenced || foreign || marker || value.startsWith(">") || value.startsWith("|")
                        || value.matches("^(?:[-*•]\\s*)?(?:如果|假如|以后|未来|引用|原文).*" ) ? line
                        : CONDITIONAL_TABLE.matcher(line).replaceAll("仅当原始需求或有效确认明确要求表格时，相应表格"));
            }
            sections.put(type, new PromptSection(type, section.title(), String.join("\n", lines)));
        }
    }
}
