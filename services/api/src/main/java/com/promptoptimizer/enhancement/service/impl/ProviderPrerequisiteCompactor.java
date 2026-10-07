package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把执行段落复写的完整未决说明集中到权威执行前提；复用归并器证明没有新增信息后才精简。
 * 不概括长句，不删除条件分支、代码、表格或四要素；新解释仍使正文保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ProviderPrerequisiteCompactor {
    private static final Pattern PENDING = Pattern.compile("(?:尚未|仍未|暂未|未)(?:确定|决定|明确|核实)|待确认|未决");
    private static final Pattern CONDITIONAL = Pattern.compile("若|如果|假如|仅当|否则|只有|仅在");
    private static final Pattern LIST_HEADER = Pattern.compile("^未决事项(?:[（(]([^（）()]*)[）)])?[：:]");
    private static final Pattern PENDING_HEADING = Pattern.compile(
            "^([^，,。；;\\r\\n]+(?:尚未|仍未|未)(?:确定|决定|核实))[，,。；;]?");

    private ProviderPrerequisiteCompactor() { }

    /**
     * 规则补回前核对已经完整登记的未决原句，不把同一分母说明再次追加到正文。
     * 只接收具名未决开头、绑定对象相同且没有新解释的整条规则；条件和实际确认不参与。
     */
    static List<String> uncoveredRules(List<String> rules, List<String> prerequisites,
                                      ConfirmedDecisionSet decisions, String rawPrompt) {
        if (prerequisites.isEmpty()) return rules;
        var merger = new PlanAmbiguityMerger(decisions, rawPrompt);
        var identity = new PendingReminderIdentity(decisions, rawPrompt);
        List<String> authoritative = merger.merge(prerequisites, prerequisites, List.of()).executionPrerequisites();
        return rules.stream().filter(rule -> {
            var heading = PENDING_HEADING.matcher(rule);
            if (CONDITIONAL.matcher(rule).find() || !heading.find()
                    || decisions.pendingDecisions().stream().noneMatch(pending -> identity.matches(heading.group(1), pending))) return true;
            var candidate = new ArrayList<>(prerequisites);
            candidate.add(rule);
            return !merger.merge(candidate, prerequisites, List.of()).executionPrerequisites().equals(authoritative);
        }).toList();
    }

    /** 每份结果只准备一次权威视图；正文候选加入后改变任何信息都原样保留。 */
    static void compact(Map<PromptSectionType, PromptSection> sections, List<String> prerequisites,
                        ConfirmedDecisionSet decisions, String rawPrompt) {
        if (prerequisites.isEmpty()) return;
        var merger = new PlanAmbiguityMerger(decisions, rawPrompt);
        var identity = new PendingReminderIdentity(decisions, rawPrompt);
        List<String> authoritative = merger.merge(prerequisites, prerequisites, List.of()).executionPrerequisites();
        compactBackgroundStatusTail(sections, prerequisites, authoritative, merger, identity, decisions);
        for (PromptSectionType type : List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT,
                PromptSectionType.CONSTRAINTS, PromptSectionType.ACCEPTANCE)) {
            PromptSection section = sections.get(type);
            if (section == null) continue;
            var retained = new ArrayList<String>();
            boolean code = false;
            boolean scoped = false;
            boolean firstContentLine = true;
            for (String line : section.content().lines().toList()) {
                String stripped = line.strip();
                boolean layoutIntro = firstContentLine && globalLayoutIntro(stripped);
                if (!stripped.isBlank()) firstContentLine = false;
                // 章节可能继承另一业务对象；不得把章节内省略对象的句子拿到全局清单中判为重复。
                if (stripped.equals("用户明确规则（须遵守平台权限边界）：")) scoped = false;
                else if (!layoutIntro && (stripped.matches("^(?:#{1,6}\\s+.+|\\*\\*.+\\*\\*)$")
                        || stripped.endsWith("：") || stripped.endsWith(":"))) scoped = true;
                if (line.strip().startsWith("```")) code = !code;
                if (code || scoped || line.strip().startsWith("```") || line.strip().startsWith("|")
                        || line.strip().startsWith(">") || !PENDING.matcher(line).find()
                        || CONDITIONAL.matcher(line).find()) {
                    retained.add(line);
                    continue;
                }
                String value = line.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
                var header = LIST_HEADER.matcher(value);
                String annotation = "";
                if (header.find()) {
                    annotation = header.group(1) == null ? "" : header.group(1);
                    value = value.substring(header.end());
                    // 完整交付边界随后由平台统一写入；只移除逐句等价的既有契约，具名状态和新条件继续核对。
                    value = withoutRepeatedDeliveryGuidance(value);
                }
                // 只拆显式列表；条件行已排除，不把分支后的共同动作当作可独立删除的句子。
                List<String> parts = java.util.Arrays.stream(value.split("[；;]"))
                        .map(String::strip).filter(part -> !part.isBlank()).toList();
                var proposed = new ArrayList<>(prerequisites);
                proposed.addAll(parts);
                boolean coveredHeadings = !parts.isEmpty() && parts.stream().allMatch(part -> coveredHeading(part, identity, decisions));
                boolean unchanged = coveredHeadings && merger.merge(proposed, prerequisites, List.of())
                        .executionPrerequisites().equals(authoritative);
                if (!unchanged) {
                    // 原需求逐字明确的插补限制仍须保留，只移除已由权威清单覆盖的首个未决断言。
                    // 不拆普通条件句，也不消费新增对象；前半句须再次通过同一绑定决定核对。
                    String[] clauses = value.split("[，,]", 2);
                    boolean rawRule = rawPrompt != null && rawPrompt.contains(value);
                    boolean imputationBoundary = clauses.length == 2 && rawRule
                            && clauses[0].matches("^是否对.+(?:使用|采用|进行)插补(?:尚未|仍未|未)(?:决定|确定)$")
                            && clauses[1].startsWith("且不同指标可能采用不同处理");
                    var primary = new ArrayList<>(prerequisites);
                    primary.add(clauses[0]);
                    if (imputationBoundary && merger.merge(primary, prerequisites, List.of())
                            .executionPrerequisites().equals(authoritative)) {
                        retained.add("插补处理：" + clauses[1].substring(1));
                    } else retained.add(compactIndependentSentences(line, prerequisites, authoritative, merger, identity, decisions));
                }
                else if (!annotation.isBlank()) retained.add("未决事项：" + annotation + "。");
            }
            String cleaned = String.join("\n", retained).strip();
            // 约束随后写入完整权威清单；任务仍须有可执行内容，不能留下空四要素。
            if (!cleaned.isBlank() || type == PromptSectionType.CONSTRAINTS) {
                sections.put(type, new PromptSection(type, section.title(), cleaned));
            }
        }
    }

    /** 显式未决列表内的通用交付指令须与平台契约整句一致，不能用关键词截掉新增业务范围。 */
    private static String withoutRepeatedDeliveryGuidance(String value) {
        var known = java.util.Arrays.stream(UnresolvedDecisionContract.DELIVERY_GUIDANCE.split("[。\\r\\n]+"))
                .map(ProviderPrerequisiteCompactor::deliverySentenceKey).collect(java.util.stream.Collectors.toSet());
        return Pattern.compile("[^。\\r\\n]+。?").matcher(value).results()
                .map(match -> match.group())
                .filter(sentence -> !known.contains(deliverySentenceKey(sentence)))
                .collect(java.util.stream.Collectors.joining());
    }

    /** 仅规范平台交付句的有限措辞，不省略对象、条件、数值或引用内容。 */
    private static String deliverySentenceKey(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).replaceAll("\\s+", "")
                .replaceAll("[。]+$", "")
                .replaceFirst("^上述未决参数在正文", "同一未决决定在正文")
                .replace("相关参数格标为", "相关参数格明确标为")
                .replace("不填入惯例、示例值或占位口径", "不得填入惯例、示例值或占位口径")
                .replace("不设置默认值或生成假结果", "不能设置默认值或生成假结果");
    }

    /** 首段的通用组织建议不引入新对象；具名机构、群组、年份或编号仍按独立业务范围保护。 */
    private static boolean globalLayoutIntro(String value) {
        return value.matches("^(?:基于(?:上述)?资料)?(?:制定|撰写|编写|形成).{2,180}"
                + "(?:按以下(?:部分|步骤|结构)组织|完成以下内容|方案需包含以下内容)[：:]$")
                && !namesBusinessScope(value);
    }

    /** 具名范围及数值需要另外核对，不把它们当成无作用域的组织语句。 */
    private static boolean namesBusinessScope(String value) {
        return value.matches(".*(?:医院|机构|公司|部门|研究组|实验组|对照组|工作区|租户|学校|学院|省|市|县|[0-9]).*");
    }

    /**
     * 背景只精简显式“未决”列表末尾的完整独立断言；同一绑定决定的解释仍保留在执行前提。
     * 资料出处、普通背景、另一业务章节、引用、条件及列表前面的事实均不参与删除。
     */
    private static void compactBackgroundStatusTail(Map<PromptSectionType, PromptSection> sections,
            List<String> prerequisites, List<String> authoritative, PlanAmbiguityMerger merger,
            PendingReminderIdentity identity, ConfirmedDecisionSet decisions) {
        PromptSection background = sections.get(PromptSectionType.BACKGROUND);
        if (background == null) return;
        var retained = new ArrayList<String>();
        boolean protectedScope = false;
        for (String line : background.content().lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith("```") || stripped.matches("^(?:#{1,6}\\s+.+|\\*\\*.+\\*\\*)$")
                    || stripped.endsWith("：") || stripped.endsWith(":")) protectedScope = true;
            int lastSeparator = Math.max(line.lastIndexOf('；'), line.lastIndexOf(';'));
            if (protectedScope || lastSeparator < 0 || CONDITIONAL.matcher(line).find()
                    || !stripped.matches("^(?:[-*•]\\s+)?未决[：:].*")) {
                retained.add(line);
                continue;
            }
            String candidate = line.substring(lastSeparator + 1).strip();
            var proposed = new ArrayList<>(prerequisites);
            proposed.add(candidate);
            retained.add(coveredHeading(candidate, identity, decisions) && merger.merge(proposed, prerequisites, List.of())
                    .executionPrerequisites().equals(authoritative) ? line.substring(0, lastSeparator) + "。" : line);
        }
        String content = String.join("\n", retained).strip();
        if (!content.isBlank()) sections.put(PromptSectionType.BACKGROUND,
                new PromptSection(background.type(), background.title(), content));
    }

    /** 完整具名的未知断言须匹配绑定对象；请求交付、引用和依赖前文的分支不能作为重复断言。 */
    private static boolean coveredHeading(String value, PendingReminderIdentity identity, ConfirmedDecisionSet decisions) {
        if (value.matches("^(?:若|如果|假如|仅|只|该|其|上述|否则|但).*" )
                || value.matches("(?s).*[“”\"`].*")) return false;
        var heading = PENDING_HEADING.matcher(value);
        return heading.find() && decisions.pendingDecisions().stream()
                .anyMatch(pending -> identity.matches(heading.group(1), pending));
    }

    /** 只拆句号结束的独立断言，保留同一长行内的实际确认、部分确认边界和其他信息。 */
    private static String compactIndependentSentences(String line, List<String> prerequisites,
            List<String> authoritative, PlanAmbiguityMerger merger, PendingReminderIdentity identity,
            ConfirmedDecisionSet decisions) {
        var sentences = Pattern.compile("[^。\\r\\n]+。?").matcher(line);
        StringBuilder retained = new StringBuilder();
        while (sentences.find()) {
            String sentence = sentences.group();
            String candidate = sentence.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
            String preservedBan = "";
            // 这条禁止默认插补的独立规则必须保留；其后的完整未决说明可单独核对，但不拆普通条件分支。
            var ban = Pattern.compile("^不得默认统一插补[；;]").matcher(candidate);
            if (ban.find()) {
                preservedBan = candidate.substring(0, ban.end());
                candidate = candidate.substring(ban.end()).strip();
            }
            String preservedBoundary = "";
            // 只确认完整性的范围仍保留；独立的一致性未决尾句须有同一绑定对象及完整说明的覆盖证据。
            var partial = Pattern.compile("(?:^|[；;])(仅确认完整性(?:指标)?分母)[，,]"
                    + "((?:跨字段(?:逻辑)?)?一致性指标(?:的)?分母(?:尚未|仍未|未)(?:决定|确定).*)$").matcher(candidate);
            if (partial.find() && (partial.start() == 0 || candidate.startsWith("完整性指标分母")
                    && !namesBusinessScope(candidate.substring(0, partial.start())))) {
                preservedBoundary = (partial.start() == 0 ? "" : candidate.substring(0, partial.start()) + "；")
                        + partial.group(1) + "。";
                candidate = partial.group(2);
            }
            if (!sentence.endsWith("。") || !coveredHeading(candidate, identity, decisions)) {
                retained.append(sentence);
                continue;
            }
            var proposed = new ArrayList<>(prerequisites);
            proposed.add(candidate);
            if (!merger.merge(proposed, prerequisites, List.of()).executionPrerequisites().equals(authoritative)) {
                retained.append(sentence);
            } else {
                if (!preservedBan.isBlank()) retained.append(preservedBan.replaceAll("[；;]$", "。"));
                retained.append(preservedBoundary);
            }
        }
        return retained.toString();
    }
}
