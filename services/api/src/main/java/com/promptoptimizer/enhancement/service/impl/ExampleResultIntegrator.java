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
        if (!contract.required()) { sections.remove(PromptSectionType.EXAMPLES); return; }
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
