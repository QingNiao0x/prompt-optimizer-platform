package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.ExampleContract;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 落实下游示例契约；无效的条件性兜底由确定的示例要求替代，不叠加相反指导。
 * 输入和预期输出供下游解释任务，平台不会在此伪造业务结果。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ExampleResultIntegrator {
    private ExampleResultIntegrator() { }

    /** 示例明确禁止时不保留模型自加段；开启时检验输入/预期输出语义而非只检验段落存在。 */
    static void integrate(Map<PromptSectionType, PromptSection> sections, ExampleContract contract) {
        if (!contract.required()) {
            sections.remove(PromptSectionType.EXAMPLES);
            // 删除示例段不能消除OUTPUT内的新示例义务；只拒绝明确的当前交付指令，不改写其余正文。
            for (var entry : sections.entrySet()) {
                if (entry.getKey() == PromptSectionType.CLARIFICATIONS || entry.getKey() == PromptSectionType.BACKGROUND) continue;
                if (hasUnrequestedInstruction(entry.getValue().content())) throw new ProviderResponseValidationException(
                        ProviderResponseValidationException.Reason.RULE_CONFLICT, "sections." + entry.getKey());
            }
            return;
        }
        var contradiction = Pattern.compile("(?:无需|不需要|不要|不提供|不输出|不包含|禁止).{0,8}(?:示例|例子)"
                + "|\\b(?:no|without)\\s+examples?\\b", Pattern.CASE_INSENSITIVE);
        for (var entry : sections.entrySet()) {
            if (entry.getKey() == PromptSectionType.EXAMPLES || entry.getKey() == PromptSectionType.CLARIFICATIONS) continue;
            if (contradiction.matcher(entry.getValue().content()).find()) throw new ProviderResponseValidationException(
                    ProviderResponseValidationException.Reason.RULE_CONFLICT, "sections." + entry.getKey());
        }
        PromptSection existing = sections.get(PromptSectionType.EXAMPLES);
        String value = existing == null ? "" : existing.content();
        if (!contract.fictionalDataAllowed()) {
            var fiction = Pattern.compile("虚构|假设数据|\\b(?:fictional|hypothetical|synthetic)\\b", Pattern.CASE_INSENSITIVE).matcher(value);
            while (fiction.find()) {
                String prefix = value.substring(0, fiction.start());
                if (!prefix.matches("(?s).*(?:不|不得|禁止|严禁|避免)[^。；;\\r\\n]{0,30}$")
                        && !prefix.matches("(?is).*\\b(?:no|not|never|without)\\b[^.;\\r\\n]{0,30}$")) {
                    throw new ProviderResponseValidationException(ProviderResponseValidationException.Reason.RULE_CONFLICT,
                            "sections.EXAMPLES");
                }
            }
        }
        PromptSection output = sections.get(PromptSectionType.OUTPUT);
        // 已在输出段明确要求下游提供配对示例时，不再补第二份相同契约；已有示范仍可保留。
        if (output != null && hasInstruction(output.content()) && existing == null) return;
        boolean conditional = Pattern.compile("仅在|必要时|如有需要|如果用户|用户要求时|资料允许时|\\b(?:if requested|if needed|when needed)\\b",
                Pattern.CASE_INSENSITIVE).matcher(value).find();
        boolean pair = Pattern.compile("输入|\\binput\\b", Pattern.CASE_INSENSITIVE).matcher(value).find()
                && Pattern.compile("预期输出|期望输出|\\bexpected output\\b", Pattern.CASE_INSENSITIVE).matcher(value).find();
        if (existing == null || conditional || !pair) sections.put(PromptSectionType.EXAMPLES,
                new PromptSection(PromptSectionType.EXAMPLES, "示例", contract.guidance()));
        else if (!hasInstruction(value) && (output == null || !hasInstruction(output.content()))) {
            // 输入/输出标签只能证明示范存在，不能证明下游收到示例要求；保留有用示范，补一条执行要求。
            sections.put(PromptSectionType.EXAMPLES, new PromptSection(PromptSectionType.EXAMPLES,
                    existing.title(), value + "\n\n" + contract.guidance()));
        }
    }

    /**
     * 识别当前肯定的示例交付，不把源码／测试、字段解释、否定、未来条件或引用升级为新增要求。
     * 无法可靠归属的表达保守保留；同一既有协议修复预算处理明确冲突，不增加重试次数。
     */
    private static boolean hasUnrequestedInstruction(String content) {
        var directive = Pattern.compile("(?:交付|提供|给出|附上|附带|附(?!件|近|属)|包含|包括|含(?!有|义|量|税)|补充|添加|展示|输出|需要|要求)"
                + "[^。；;！？!?\\r\\n]{0,100}(?:示例|示范|例子)"
                + "|\\b(?:include|provide|give|add|show)\\b[^.;!?\\r\\n]{0,100}\\bexamples?\\b", Pattern.CASE_INSENSITIVE);
        String fence = null;
        boolean deferredOrQuotedScope = false;
        for (String line : content.lines().toList()) {
            String text = line.strip();
            var marker = Pattern.compile("^(`{3,}|~{3,})(.*)$").matcher(text);
            if (marker.matches()) {
                String token = marker.group(1);
                if (fence == null) fence = token;
                else if (token.charAt(0) == fence.charAt(0) && token.length() >= fence.length()
                        && marker.group(2).isBlank()) fence = null;
                continue;
            }
            String scopeText = text.replaceFirst("^(?:[-*•]\\s*|\\d+[.)、]\\s*)", "");
            // 跨行未来／引用块不能在下一行重新变成当前要求；未知标题不擅自解除这一保护。
            if (scopeText.matches("^(?:如果|假如|若|仅当|以后|未来|待确认).*[：:]$")
                    || scopeText.matches("^(?:引用|原文|历史任务|历史方案|旧方案|示例|例子)[：:]$")
                    || text.contains("“") && !text.contains("”") || text.contains("‘") && !text.contains("’")) {
                deferredOrQuotedScope = true;
            }
            if (fence != null || text.startsWith(">") || text.startsWith("|")
                    || deferredOrQuotedScope
                    || line.startsWith("    ") || line.startsWith("\t") || text.matches(".*[\"“”‘’].*")
                    || text.matches("^(?:如果|假如|若|仅当|以后|未来|待确认).*")) continue;
            var found = directive.matcher(text);
            while (found.find()) {
                String prefix = text.substring(0, found.start());
                String phrase = found.group();
                // “代码（含调用示例）”是当前交付；裸“含”只认列表/括注起点，不把叙述词升级为指令。
                if (phrase.startsWith("含") && !prefix.isBlank() && !prefix.matches("(?s).*[（(，,：:]\\s*$")) {
                    found.region(found.start() + 1, text.length());
                    continue;
                }
                if (insideNegatedDelivery(prefix)
                        // 外层“输出”不能跨过后面的否定交付，误把并列宾语中的示例变成肯定义务。
                        || ExampleContract.negatesExampleDelivery(phrase)
                        || phrase.matches("(?s).*(?:测试|验证)(?:用例)?(?:示例|示范|例子)$")
                        || prefix.matches("(?is).*(?:不|不要|不得|禁止|无需|不需要|不要求|不提供|不包含)(?:再|额外)?$")
                            && !prefix.matches("(?s).*(?:不得不|不能不|不可不)$")
                        || prefix.matches("(?is).*\\b(?:no|not|never|without|do not)\\s*$")
                        || phrase.matches("(?s).*(?:不(?:要|得)?(?:提供|输出|附带|包含|包括|含|需要|要求|添加|增加|新增)|不加|无需|禁止)[^，,。；;]{0,16}(?:示例|示范|例子)$")) {
                    // 被否定的前半句不能让同一行后面的独立肯定要求一起跳过。
                    found.region(found.start() + 1, text.length());
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** 括注继承父句的交付否定；先前独立否定不跨逗号扩展，“不得不”仍是肯定要求。 */
    private static boolean insideNegatedDelivery(String prefix) {
        int opening = Math.max(prefix.lastIndexOf('（'), prefix.lastIndexOf('('));
        int closing = Math.max(prefix.lastIndexOf('）'), prefix.lastIndexOf(')'));
        if (opening < 0 || opening < closing) return false;
        String parent = prefix.substring(0, opening);
        if (parent.matches("(?s).*(?:不得不|不能不|不可不)(?:交付|提供|输出|生成)[^。；;，,]*$")) return false;
        return parent.matches("(?s).*(?:不|不要|不得|禁止|无需|不需要|不要求)(?:再|额外)?"
                + "(?:交付|提供|给出|附上|附带|包含|包括|输出|生成)[^。；;，,]*$");
    }

    /** 排除示例标签、引用和代码内的祈使句；配对标签与真实交付指令必须同时存在。 */
    private static boolean hasInstruction(String content) {
        boolean pair = Pattern.compile("输入|\\binput\\b", Pattern.CASE_INSENSITIVE).matcher(content).find()
                && Pattern.compile("预期输出|期望输出|\\bexpected output\\b", Pattern.CASE_INSENSITIVE).matcher(content).find();
        if (!pair) return false;
        var directive = Pattern.compile("(?:提供|给出|附上|包含|展示|输出)[^。；;\\r\\n]{0,100}(?:示例|例子)"
                + "|\\b(?:provide|include|show|give)\\b[^.;\\r\\n]{0,100}\\b(?:examples?|samples?)\\b", Pattern.CASE_INSENSITIVE);
        boolean fenced = false;
        for (String line : content.split("\\R")) {
            String text = line.strip();
            if (text.startsWith("```") || text.startsWith("~~~")) { fenced = !fenced; continue; }
            if (fenced || text.startsWith(">") || text.startsWith("|")
                    || text.matches("(?i)^(?:输入|预期输出|期望输出|input|expected output)(?:示例| example)?\\s*[:：].*")) continue;
            if (directive.matcher(text).find()) return true;
        }
        return false;
    }
}
