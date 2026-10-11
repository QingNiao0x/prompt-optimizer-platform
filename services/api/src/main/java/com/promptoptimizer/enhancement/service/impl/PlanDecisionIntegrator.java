package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.EffectiveTaskView.ExecutionRequirement;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 将有效确认落实在责任段；已有完整执行表达不再追加答卷或协议标签。
 * 只证明保守的句法等价，不能用关键词相似抹去否定、数值、机构或新增条件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanDecisionIntegrator {
    private static final Pattern NON_CURRENT = Pattern.compile(
            "(?:如果|假如|若以后|未来|待确认|尚未|暂不确定|建议|候选|可选|例如|不采用|不使用|不选择|未采用|未使用|未选|不能采用|不得采用|不按|不以|不是|不为|并非|不属于)|^(?:示例|例子)[：:]"
                    + "|\\b(?:if|might|not selected|do not use)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL = Pattern.compile("如果|假如|假设|(?:当|若|仅|只).*(?:时|则|才)|超过|达到|[<>=]|\\b(?:if|when|unless)\\b", Pattern.CASE_INSENSITIVE);

    private PlanDecisionIntegrator() { }

    /** 同一确认在四要素正文内有权威表达即视为覆盖；示例和验收提及不能代替执行要求。 */
    static void integrate(Map<PromptSectionType, PromptSection> sections, EffectiveTaskView task) {
        for (ExecutionRequirement requirement : task.executionRequirements()) {
            List<String> missing = missingStatements(sections, requirement);
            if (missing.isEmpty()) continue;
            PromptSection current = sections.get(requirement.destination());
            // 多项回答分别核对；只补缺项，不因一个子项缺失就复制整份答案和已落实的要求。
            sections.put(current.type(), new PromptSection(current.type(), current.title(),
                    current.content() + missing.stream().map(value -> "\n- " + value)
                            .collect(java.util.stream.Collectors.joining())));
        }
    }

    /** 完整答案子句可自然加入正文；裸取值另需完整对象，避免把甲机构的同值当成乙机构确认。 */
    static boolean covered(Map<PromptSectionType, PromptSection> sections, ExecutionRequirement requirement) {
        return !requirement.statement().isBlank() && missingStatements(sections, requirement).isEmpty();
    }

    /** 强边界子句可分别落实；带条件或限定对象的回答保持整体，不能拆掉条件后误认覆盖。 */
    private static List<String> missingStatements(Map<PromptSectionType, PromptSection> sections,
            ExecutionRequirement requirement) {
        List<String> statements = executableClauses(requirement);
        List<String> body = sections.entrySet().stream()
                .filter(entry -> entry.getKey() == PromptSectionType.BACKGROUND || entry.getKey() == PromptSectionType.TASK
                        || entry.getKey() == PromptSectionType.OUTPUT || entry.getKey() == PromptSectionType.CONSTRAINTS)
                .flatMap(entry -> currentSentences(entry.getValue().content()).stream())
                .toList();
        return statements.stream().filter(statement -> body.stream().noneMatch(sentence -> covers(sentence, statement, requirement)))
                .toList();
    }

    /** 保留原句条件与数值，只拆明确并列的完整指令；短答的对象由 statement 保留。 */
    private static List<String> executableClauses(ExecutionRequirement requirement) {
        String statement = requirement.statement();
        boolean scoped = CONDITIONAL.matcher(statement).find()
                || requirement.question().matches("(?s).*(?:\\d|机构|组织|公司|企业|医院|版本|生效).*");
        String split = scoped ? "(?!)" : "(?<=[。！？!?])|[；;]|[，,](?=(?:并)?(?:提供|展示|要求处理))";
        return java.util.Arrays.stream(statement.split(split)).map(String::strip).filter(value -> !value.isBlank())
                // 并列交付项只在无条件、无机构/版本限定时拆分；不拆数值或指标的条件表达。
                .flatMap(value -> !scoped && value.matches("^(?:并)?提供[^，,。；;]+(?:及|以及)[^，,。；;]+[。.]?$")
                        ? java.util.Arrays.stream(value.replaceFirst("^(?:并)?提供", "").split("以及|及"))
                            .map(part -> "提供" + part)
                        : java.util.stream.Stream.of(value))
                .toList();
    }

    /** 比较同一完整执行子项；状态、否定和条件不能仅因包含取值就被当成已经落实。 */
    private static boolean covers(String sentence, String statement, ExecutionRequirement requirement) {
        String expected = canonical(statement);
        String actual = canonical(sentence);
        if (expected.isBlank()) return false;
        if (NON_CURRENT.matcher(sentence).find() && !actual.equals(expected)) return false;
        if (CONDITIONAL.matcher(sentence).find() && !CONDITIONAL.matcher(statement).find()) return false;
        if (completeValue(actual, expected)) return true;
        // 划分维度题中，“按年龄组比较/划分”均须保留完整分组，同时正文须仍要求比较。
        // 带机构、年份或版本的题继续使用完整对象，不用宽泛的分组词消除其限定。
        if (requirement.question().matches("(?s).*(?:划分|分组|维度).*" )
                && !requirement.question().matches("(?s).*(?:\\d|机构|组织|公司|企业|医院|版本|生效).*")) {
            var grouping = Pattern.compile("^(.{2,80}?)按(.+[（(].+[）)])(?:比较|划分|分组)[。.]?$").matcher(statement.strip());
            if (grouping.matches() && actual.contains("比较")
                    && completeValue(actual, canonical(grouping.group(1) + "按" + grouping.group(2)))) return true;
        }
        // 对象相同的赋值句允许“亚类为/亚类包括”和“研究地区：中国/研究地区为中国”。
        // 对象、完整取值和所有条件仍逐字核对，不能把另一个机构/年份的同值当作覆盖。
        var assignment = Pattern.compile("^(.{2,80}?)(?:：|:|采用|使用|按照|按|为|包括|包含)(.+)$").matcher(statement.strip());
        if (!assignment.matches()) return false;
        String subject = canonical(assignment.group(1));
        String value = canonical(assignment.group(2));
        return Pattern.compile(leftBoundary(subject) + Pattern.quote(subject)
                + "(?:采用|使用|按照|按|为|包括|包含)?" + Pattern.quote(value)
                + rightBoundary(value)).matcher(actual).find()
                || completeValue(actual, value + subject);
    }

    /** 引用、表格和代码中的取值不是当前执行要求，不能被用来掩盖正文缺项。 */
    private static List<String> currentSentences(String content) {
        var body = new java.util.ArrayList<String>();
        String fence = null;
        for (String line : content.lines().toList()) {
            String value = line.strip();
            if (value.startsWith("```") || value.startsWith("~~~")) {
                String marker = value.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
                continue;
            }
            if (fence != null || value.startsWith(">") || value.startsWith("|")) continue;
            body.addAll(java.util.Arrays.asList(value.split("(?<=[。！？!?])|[；;]")));
        }
        return List.copyOf(body);
    }

    /** 确认与原始规则同用保守句法覆盖，避免保真兜底把已落实的完整规则再补一遍。 */
    static boolean coveredRule(Map<PromptSectionType, PromptSection> sections, String rule) {
        String expected = canonical(rule);
        return sections.values().stream().filter(section -> section.type() != PromptSectionType.CLARIFICATIONS
                        && section.type() != PromptSectionType.EXAMPLES && section.type() != PromptSectionType.ACCEPTANCE)
                .flatMap(section -> currentSentences(section.content()).stream())
                .anyMatch(sentence -> canonical(sentence).equals(expected));
    }

    /** 完整数字、操作符和 ASCII 标识符参与边界，500 不能覆盖 5000，R 不能覆盖 RStudio。 */
    private static boolean completeValue(String actual, String expected) {
        return Pattern.compile(leftBoundary(expected) + Pattern.quote(expected) + rightBoundary(expected))
                .matcher(actual).find();
    }

    /** 中文子句可紧接 ICD 等标识符；只有 ASCII 取值边缘需要标识符/数值边界。 */
    private static String leftBoundary(String value) {
        return !value.isEmpty() && value.substring(0, 1).matches("[A-Za-z0-9_.<>=]")
                ? "(?<![A-Za-z0-9_.<>=])" : "";
    }

    /** 后缀仍区分 R/RStudio、500/5000；中文单位由完整取值字面匹配保证。 */
    private static String rightBoundary(String value) {
        return !value.isEmpty() && value.substring(value.length() - 1).matches("[A-Za-z0-9_.<>=]")
                ? "(?![A-Za-z0-9_.<>=])" : "";
    }

    /** 只归一化中文指令语法和分段符号，不归一化比较符、标识符、数值或否定。 */
    private static String canonical(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip()
                .replaceAll("^(?:[-*•]\\s*|\\d+[.)、]\\s*)", "")
                .replaceFirst("^(?:本次|这次|当前|已经确认|已确认)(?=采用|使用|按|提供)", "")
                .replaceFirst("^(?:采用|使用|按照|按)(?=\\s*(?:[A-Za-z]|升序|降序|整数值))\\s*", "")
                .replaceAll("(?<=数据)(?:来自|来源于|取自)", "来源")
                .replaceAll("(?<=[A-Za-z0-9])\\s*用于(?=分解|分析|计算)", "")
                .replaceAll("(?:要求|需要|需)?(?:正确|妥善)?处理", "处理")
                .replaceAll("编写(?:一个|一份)", "编写")
                .replaceFirst("^(?:并)?提供", "")
                .replaceFirst("^并(?=展示)", "")
                .replaceAll("[：:，,。!！?？;；`*]", "")
                // 只去掉中文与英文/数字之间的排版空白；ASCII 标识符内部空格继续有区分度。
                .replaceAll("(?<=[\\p{IsHan}])\\s+|\\s+(?=[\\p{IsHan}])", "")
                .replaceAll("([A-Za-z][A-Za-z0-9_/-]*)(分解|分析|计算|排序)方法(?:分析|计算|处理)", "$1$2")
                .replaceAll("(分解|分析|计算)\\1", "$1")
                .replaceFirst("\\.$", "").strip();
    }
}
