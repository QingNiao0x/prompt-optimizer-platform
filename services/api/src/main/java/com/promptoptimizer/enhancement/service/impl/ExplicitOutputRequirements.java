package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 保留用户显式标记的输出要求块，复用来源投影和最终输出补全，不推断领域字段或默认表格。
 * 无标签的普通任务仍完整交给原链路；引用、示例、未来及受保护内容不能成为新交付授权。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ExplicitOutputRequirements {
    private static final String OUTPUT_TITLE = "输出要求|期望输出|交付要求|输出|交付";
    private static final Pattern OUTPUT = Pattern.compile("^(?:#{1,6}\\s+)?(?:\\*\\*)?(?:" + OUTPUT_TITLE
            + ")(?:\\*\\*)?\\s*(?:[：:](.*))?$");
    private static final Pattern CURRENT = Pattern.compile("^(?:#{1,6}\\s+)?(?:\\*\\*)?(?:任务|任务目标|当前任务|本次任务|需求)(?:\\*\\*)?[：:]?.*$");
    private static final Pattern SOURCE = Pattern.compile("^(?:#{1,6}\\s+)?(?:\\*\\*)?(?:参考资料|示例|例子|原文|材料|资料|已知资料"
            + "|输入数据|输入文本|待处理文本|待翻译文本|翻译以下(?:内容|文字|文本)|历史任务|未来任务|其他任务)(?:\\*\\*)?[：:]?.*$");
    private static final Pattern SECTION = Pattern.compile("^(?:#{1,6}\\s+.+|(?:背景|已知资料|任务|任务目标|需求|约束|约束条件|验收|验收标准)[：:].*)$");

    private ExplicitOutputRequirements() { }

    /** 原样保存完整块及其条件；只依据用户明示的段落职责，不按排名、数据源等业务词删改字段。 */
    static List<String> declarations(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        List<String> block = new ArrayList<>();
        String fence = null;
        boolean source = false;
        int outputLevel = 0;
        for (String line : raw.lines().toList()) {
            String value = line.strip();
            if (value.startsWith("```") || value.startsWith("~~~")) {
                String marker = value.substring(0, 3);
                if (!block.isEmpty()) block.add(line);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
                continue;
            }
            if (fence != null) {
                if (!block.isEmpty()) block.add(line);
                continue;
            }
            if (value.startsWith(">") || line.startsWith("    ") || line.startsWith("\t")) {
                // 输出块内的缩进结构和引用是用户所需格式的一部分；块外不据它们建立交付。
                if (!block.isEmpty()) block.add(line);
                continue;
            }
            int level = 0;
            while (level < value.length() && value.charAt(level) == '#') level++;
            if (!block.isEmpty() && outputLevel > 0 && level > outputLevel) {
                block.add(line);
                continue;
            }
            if (SOURCE.matcher(value).matches() || value.matches("^(?:如果|假如|若|以后|未来|待确认).*[：:]$")) {
                finish(block, result); source = true; continue;
            }
            // 资料中也可能出现“任务/输出”标签；只有明确回到本次任务才离开资料范围。
            String currentHeading = value.replaceFirst("^#{1,6}\\s+", "").replace("**", "");
            if (CURRENT.matcher(value).matches() && (!source
                    || currentHeading.startsWith("本次任务") || currentHeading.startsWith("当前任务"))) {
                finish(block, result); source = false;
            }
            var heading = OUTPUT.matcher(value);
            if (!source && heading.matches()) {
                finish(block, result);
                block.add(line);
                outputLevel = level;
            } else if (SECTION.matcher(value).matches()) {
                finish(block, result);
            } else if (!block.isEmpty()) {
                block.add(line);
            }
        }
        finish(block, result);
        return List.copyOf(result);
    }

    /** 空标题不变成占位交付；沿用保真器同一隐私和越权校验，保持原始字面内容。 */
    private static void finish(List<String> block, List<String> result) {
        if (block.isEmpty()) return;
        String text = String.join("\n", block).strip();
        String first = block.getFirst().strip();
        var heading = OUTPUT.matcher(first);
        boolean body = heading.matches() && heading.group(1) != null && !heading.group(1).isBlank()
                || block.stream().skip(1).anyMatch(line -> !line.isBlank());
        if (body && new RequirementFidelityGuard().mayProjectUserRequirement(text) && !result.contains(text)) result.add(text);
        block.clear();
    }

    /** 来源引用或完整当前执行表述已覆盖时不补写；遗漏只补回输出，不将交付格式降为约束。 */
    static Set<String> complete(Map<PromptSectionType, PromptSection> sections, String raw, Set<String> deliveredSources) {
        List<String> outputs = declarations(raw);
        // 记录仅在同一责任段原句仍存在时有效；最终压缩若移除了块，必须重新补回。
        List<String> missing = outputs.stream().filter(value -> !deliveredSources.contains(value)
                        || !sections.get(PromptSectionType.OUTPUT).content().contains(value))
                .filter(value -> !TaskConstraintCoverage.uncovered(sections, List.of(value)).isEmpty()).toList();
        if (!missing.isEmpty()) {
            var output = sections.get(PromptSectionType.OUTPUT);
            String separator = output.content().isBlank() ? "" : "\n\n";
            sections.put(output.type(), new PromptSection(output.type(), output.title(),
                    output.content() + separator + String.join("\n\n", missing)));
        }
        return Set.copyOf(outputs);
    }

    /** 完整声明已在责任段交付时，其内部原规则同样被保留，不再到约束部分另抄一遍。 */
    static Set<String> rulesOf(Iterable<String> outputs) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String output : outputs) {
            result.add(output);
            result.addAll(new RequirementFidelityGuard().explicitRules(output, List.of()));
        }
        return Set.copyOf(result);
    }
}
