package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 只省去正文已完整承担的追加义务，不删除模型正文、用户原句、题答或约束来源。
 * 复合句的后项必须有完整动作与对象；省略主语的字数条件继续与前面的交付对象整体核对。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class AdditionalRuleProjection {
    private static final String FOREIGN_TASK_SCOPE = "另一|其他(?:研究|任务|项目)|[甲乙丙丁](?:研究|任务)";
    private static final Pattern SCOPED = Pattern.compile(
            "如果|假如|假设|若|仅当|只要|只有|否则|但|除非|除外|例外|仅限|仅适用|只对|只在"
                    + "|以后|未来|待确认|尚未|未确定|(?:时|前|后|条件下)[，,]|[<>=]"
                    + "|示例|例如|例子|引用|原文|" + FOREIGN_TASK_SCOPE
                    + "|不必遵守|无需遵守|不得要求|不要求|不是要求|并非要求|[\"“”‘’`?？]"
                    + "|\\b(?:if|when|unless|not selected)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLETE_ACTION = Pattern.compile(
            "^(?:请|必须|务必|应|须|需)?(?:只|仅)?(?:不(?:得|要|应|允许|可)?|禁止)?"
                    + "(?:输出|交付|提供|返回|保留|记录|打印|泄露|编造|添加|使用|修改|删除|读取|生成|说明|校验|处理|运行|执行|采用)"
                    + "[^，,；;。！？!?]+[。！？!?]?$" );
    private static final Pattern NEGATED_PREFIX = Pattern.compile(
            "^(?:请|必须|务必|应|须|需)?(?:只|仅)?(?:不(?:得|要|应|允许|可)?|禁止)" );
    private static final Pattern CURRENT_HEADING = Pattern.compile(
            "(?:本任务|本次任务|当前任务|本研究|本次研究|当前研究|任务|输出|交付|交付以下内容|约束|约束条件|任务相关约束|Java21交付)[：:]?" );
    // 无属性的小写标签只是行内数据标识；数字比较、泛型或紧邻英文变量的运算式继续保守处理。
    private static final Pattern INLINE_TAG = Pattern.compile("(?<![A-Za-z0-9_])</?[a-z][a-z0-9-]*>(?![A-Za-z0-9_])");

    private AdditionalRuleProjection() { }

    /** 只补来源中尚未落实的完整后项；无法证明独立时继续保留完整原句。 */
    static List<String> uncovered(Map<PromptSectionType, PromptSection> sections, String rule) {
        if (rule == null || rule.isBlank()) return List.of();
        Map<PromptSectionType, PromptSection> evidence = currentEvidence(sections, scoped(rule));
        List<String> assertions = evidence.values().stream().flatMap(section -> section.content().lines())
                .flatMap(line -> independentClauses(line).stream()).map(AdditionalRuleProjection::completeStatement).toList();
        // 新投影只比较完整同向断言；任意子串可能省略前置对象、否定或修饰，不能作为删去来源要求的依据。
        return independentClauses(rule).stream()
                .filter(clause -> !assertions.contains(completeStatement(clause))).toList();
    }

    /** 旧整句入口只接收当前CONSTRAINTS作用域，不能先在示例/条件块中命中原句后绕过新判断。 */
    static String currentConstraintText(Map<PromptSectionType, PromptSection> sections) {
        return currentTaskScope(sections.get(PromptSectionType.CONSTRAINTS));
    }

    /**
     * 任务规则的当前作用域按整行保守建立；一旦进入条件/历史/未知块，同段不再猜测恢复。
     * 仅接受TASK首个无条件“实现/编写…功能”导语，不把旧方案、参考或条件说明视作新任务。
     * 原始内容和所有来源不变，本方法只返回可用于省略重复追加的证据副本。
     */
    private static String currentTaskScope(PromptSection section) {
        List<String> current = new ArrayList<>();
        boolean currentScope = true, firstContent = true;
        String fence = null;
        for (String line : section.content().lines().toList()) {
            String value = line.strip();
            boolean first = firstContent;
            if (!value.isBlank()) firstContent = false;
            if (value.startsWith("```") || value.startsWith("~~~")) {
                String marker = value.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
                continue;
            }
            if (fence != null || value.startsWith(">") || value.startsWith("|")
                    || line.startsWith("    ") || line.startsWith("\t")) continue;
            String heading = value.replaceFirst("^#{1,6}\\s*", "").replaceFirst("^\\*\\*(.+?)\\*\\*$", "$1");
            // 元信息与完整断言使用相同列表前缀，数字列表不能隐藏跨行条件或对象边界。
            String scopeLine = heading.replaceFirst("^(?:[-*•]\\s*|\\d+[.)、]\\s*)", "");
            boolean reference = scopeLine.matches(".*(?:仅供参考|历史(?:任务|规则|方案)|旧(?:任务|规则|方案)|条件).*" );
            if (value.startsWith("#") || heading.endsWith("：") || heading.endsWith(":")) {
                boolean initialTask = first && section.type() == PromptSectionType.TASK && !scoped(value) && !reference
                        && value.matches("^(?:请)?(?:实现|编写)[^：:]{1,200}功能[：:]$");
                currentScope = currentScope && (initialTask || CURRENT_HEADING.matcher(heading).matches());
                continue;
            }
            // 条件可用逗号或换行引出，不要求必须出现冒号；引用/历史的无冒号标题同样不是当前规则。
            if (scopeLine.matches("^(?:如果|假如|若|仅当|只要|只有|仅限|仅针对|只对|只在|未来|以后|待确认).*" )
                    || scopeLine.matches("^(?:" + FOREIGN_TASK_SCOPE + ").*")
                    || scopeLine.matches("^(?:示例|例子|引用|原文|历史任务|历史规则|历史方案|旧任务|旧规则|旧方案|仅供参考)(?:$|[：:（(].*)")) {
                currentScope = false;
                continue;
            }
            if (currentScope) current.add(line);
        }
        return String.join("\n", current);
    }

    /**
     * 任务来源仅按完整当前断言覆盖，不拆用户句法或将同词的另一对象拼成全局规则。
     * 行尾分号与句号只作为断句差异；内部标点、数字、比较符、标识符及条件均保留。
     */
    static boolean coveredCompleteRule(Map<PromptSectionType, PromptSection> sections, String rule) {
        if (rule == null || rule.isBlank() || rule.lines().count() != 1 || scoped(rule)) return false;
        String expected = completeStatement(rule);
        return currentEvidence(sections, false).values().stream()
                .flatMap(section -> section.content().lines()).anyMatch(line -> completeStatement(line).equals(expected));
    }

    /** 只去除列表排版及末尾断句号，不删内部逗号或把并列否定变成两个独立动作。 */
    private static String completeStatement(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).strip()
                .replaceFirst("^(?:[-*•]\\s*|\\d+[.)、]\\s*)", "")
                .replaceAll("(?<=[\\p{IsHan}])\\s+|\\s+(?=[\\p{IsHan}])", "")
                .replaceFirst("[。；;]$", "").strip()
                // 仅接收同一完整工程测试要求的语法助词差异，带对象修饰、条件或例外的句子不命中。
                .replaceFirst("^(为核心逻辑补充正常、异常和边界场景)的测试$", "$1测试");
    }

    /**
     * 内部Java21契约的完整句与分号子项有固定独立职责；只投影缺项，不删掉整个契约。
     * 用户条件句不走此拆分入口，BigDecimal、测试文件与实际验证的条件仍逐项保留。
     */
    static List<String> uncoveredJavaDelivery(Map<PromptSectionType, PromptSection> sections, String guidance) {
        if (guidance == null || guidance.isBlank()) return List.of();
        String body = guidance.replaceFirst("^Java21交付[：:]", "");
        return java.util.Arrays.stream(body.split("(?<=。)|[；;]"))
                .filter(value -> !value.isBlank()).flatMap(value -> uncovered(sections, value).stream()).toList();
    }

    /** 逗号/分号后的动作须有独立对象与方向；共享否定或长度等省略对象的后项保持整体。 */
    private static List<String> independentClauses(String rule) {
        if (scoped(rule)) return List.of(rule);
        List<String> clauses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean negatedScope = false;
        for (String fragment : rule.split("(?<=[，,；;])", -1)) {
            String action = fragment.strip().replaceFirst("^(?:[-*•]\\s*|\\d+[.)、]\\s*)", "")
                    .replaceFirst("[，,；;]$", "");
            boolean negated = NEGATED_PREFIX.matcher(action).find();
            if (!current.isEmpty() && COMPLETE_ACTION.matcher(action).matches()) {
                // 前置否定可能同时约束后项；省略否定的动作不能投影成新的正向义务。
                if (negatedScope && !negated) return List.of(rule);
                clauses.add(current.toString().replaceFirst("[，,；;]$", "").strip());
                current.setLength(0);
            }
            negatedScope |= negated;
            current.append(fragment);
        }
        if (!current.isEmpty()) clauses.add(current.toString().strip());
        return List.copyOf(clauses);
    }

    /** 禁止添加示例是当前要求；ASCII行内标识符是排版，不是包含中文执行指令的引用。 */
    private static boolean scoped(String text) {
        String value = text.replaceAll("((?:不(?:得|要|应|允许)?|禁止)(?:输出|提供|生成|添加))(?:示例|例子)", "$1演示内容")
                .replaceAll("`([A-Za-z0-9_.$<>(),\\[\\]:\\s=/-]+)`", "$1");
        // 只在作用域判断副本中忽略标签尖括号，正文与覆盖比较保留原值；条件、否定、引用及例外仍参与保护。
        value = INLINE_TAG.matcher(value).replaceAll("行内标签");
        return SCOPED.matcher(value).find();
    }

    /**
     * 覆盖证据只来自可复制的执行正文。未知/条件标题不能让下面的同值变成全局要求，
     * 代码、引用、示例与验收提及继续使用既有过滤；带作用域的原规则只接受原有完整匹配。
     */
    private static Map<PromptSectionType, PromptSection> currentEvidence(
            Map<PromptSectionType, PromptSection> sections, boolean scopedRule) {
        Map<PromptSectionType, PromptSection> evidence = new EnumMap<>(PromptSectionType.class);
        for (var entry : sections.entrySet()) {
            if (!List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS).contains(entry.getKey())) continue;
            List<String> current = new ArrayList<>();
            boolean currentScope = true;
            String fence = null;
            String evidenceContent = currentTaskScope(entry.getValue());
            for (String line : evidenceContent.lines().toList()) {
                String value = line.strip();
                if (value.startsWith("```") || value.startsWith("~~~")) {
                    String marker = value.substring(0, 3);
                    if (fence == null) fence = marker;
                    else if (fence.equals(marker)) fence = null;
                    continue;
                }
                if (fence != null || value.startsWith(">") || value.startsWith("|")
                        || line.startsWith("    ") || line.startsWith("\t")) continue;
                String heading = value.replaceFirst("^#{1,6}\\s*", "").replaceFirst("^\\*\\*(.+?)\\*\\*$", "$1");
                if (value.startsWith("#") || heading.endsWith("：") || heading.endsWith(":")) {
                    currentScope = CURRENT_HEADING.matcher(heading).matches();
                    continue;
                }
                if (!currentScope) continue;
                // 分号两侧可能共用前置条件或后置例外，先核对整行，不能先拆出貌似无条件的后半句。
                if (!scopedRule && scoped(value)) continue;
                // 分号可能继续承接否定，须留给独立动作判断；完整句可按句末标点分别承担方向。
                java.util.Arrays.stream(value.split("(?<=[。！？!?])"))
                        .filter(sentence -> scopedRule || !scoped(sentence)).forEach(current::add);
            }
            evidence.put(entry.getKey(), new PromptSection(entry.getKey(), entry.getValue().title(), String.join("\n", current)));
        }
        return evidence;
    }
}
