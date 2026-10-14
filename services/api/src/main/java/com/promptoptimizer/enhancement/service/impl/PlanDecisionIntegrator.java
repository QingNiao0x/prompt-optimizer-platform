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
        if (independentCoverage(sentence, statement, requirement.subject())) return true;
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
        boolean foreignBlock = false;
        for (String line : content.lines().toList()) {
            String value = line.strip();
            if (value.startsWith("```") || value.startsWith("~~~")) {
                String marker = value.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
                continue;
            }
            if (fence != null || value.startsWith(">") || value.startsWith("|")) continue;
            String heading = value.replaceFirst("^#{1,6}\\s*", "").replaceFirst("^\\*\\*(.+?)\\*\\*$", "$1");
            // 换行不能把另一研究、引用或示例里的声明变成本任务来源；只有明确当前任务标题恢复作用域。
            if (heading.matches("(?s).*(?:^|[。；;])(?:另一(?:项)?研究|其他研究|甲研究|乙研究|示例|例子|引用|原文|未来方案|以后确认)[：:]?\\s*$")) {
                foreignBlock = true;
                continue;
            }
            if (heading.matches("(?:本研究|本次研究|当前研究|本任务|本次任务)[：:]?")) foreignBlock = false;
            if (foreignBlock) continue;
            // 条件与后面的分号子句共享作用域，不能拆掉前置条件后把后半句当成无条件要求。
            if (value.matches("^(?:[-*•]\\s*)?(?:如果|假如|若以后|未来|以后确认|例如|示例|引用|原文)[：:,，]?.*")) {
                body.add(value);
            } else {
                body.addAll(java.util.Arrays.asList(value.split("(?<=[。！？!?])|[；;]")));
            }
        }
        return List.copyOf(body);
    }

    /** 确认与原始规则同用保守句法覆盖，避免保真兜底把已落实的完整规则再补一遍。 */
    static boolean coveredRule(Map<PromptSectionType, PromptSection> sections, String rule) {
        return uncoveredRuleClauses(sections, rule).isEmpty();
    }

    /** 复合规则只补缺少的完整独立子项；条件和非独立对象语句保持原来的整体边界。 */
    static List<String> uncoveredRuleClauses(Map<PromptSectionType, PromptSection> sections, String rule) {
        var introduction = researchIntroductionClauses(sections, rule);
        if (introduction.isPresent()) return introduction.get();
        List<String> body = sections.values().stream().filter(section -> section.type() != PromptSectionType.CLARIFICATIONS
                        && section.type() != PromptSectionType.EXAMPLES && section.type() != PromptSectionType.ACCEPTANCE)
                .flatMap(section -> currentSentences(section.content()).stream()).toList();
        // 只拆完整句和明确并列子句；条件规则保持整体，避免把条件内的同值当全局要求。
        // 禁用假设数据是当前禁令，不能因“假设”二字被误识别为假设分支；其他条件仍按原句核对。
        String conditionText = rule.replaceAll("((?:不得|禁止|不能|不允许|不要)(?:使用|编造))假设(?=数据|样本|结果)", "$1模拟");
        boolean scoped = CONDITIONAL.matcher(conditionText).find() || rule.matches("(?s).*(?:但|除非|除外|例外|仅限).*" );
        // 原件中的全局禁代码与后续完整禁令互相独立；只拆这些有完整动作的子句，不拆条件、例外和省略对象。
        boolean independentProhibitions = rule.matches("^不提供代码[，,](?:不得|禁止|不能|不允许).*");
        String split = scoped ? "(?!)" : "(?<=[。！？!?])|[；;]"
                + (independentProhibitions ? "|[，,](?=(?:不得|禁止|不能|不允许))" : "");
        var independent = PlanAnswerSemantics.independentStatements(rule);
        List<String> clauses = independent.isEmpty() ? java.util.Arrays.stream(rule.split(split)).map(String::strip)
                .filter(value -> !value.isBlank()).toList() : independent.stream().map(PlanAnswerSemantics.IndependentStatement::text).toList();
        return clauses.stream().filter(clause -> body.stream().noneMatch(sentence -> {
            String expected = canonical(clause), actual = canonical(sentence);
            if (actual.equals(expected)) return true;
            if (independentCoverage(sentence, clause, "用户说明") || independentCoverage(sentence, clause, "委托要求")) return true;
            // 同向完整子句可出现在较长执行句中；新增条件、转述和反向要求不能免除兜底。
            if (sentence.matches("(?s).*(?:如果|假如|若|未来|例如|示例|引用|原文|另一研究|其他研究|甲研究|乙研究|不是|并非|不必遵守|无需遵守).*")) return false;
            if (CONDITIONAL.matcher(sentence).find() && !CONDITIONAL.matcher(clause).find()) return false;
            int position = actual.indexOf(expected);
            if (position > 0 && actual.substring(0, position).matches("(?s).*(?:不得|不能|禁止|不要|不应(?:当)?|不允许|无需|不必|不|未)$")) return false;
            return completeValue(actual, expected);
        })).toList();
    }

    /** 仅接受已命名的本任务独立状态和委托；不把其他研究、条件、未来或否定动作作覆盖证据。 */
    private static boolean independentCoverage(String sentence, String statement, String subject) {
        if (!List.of("用户说明", "委托要求", "未确认边界").contains(subject)) return false;
        String actual = java.text.Normalizer.normalize(sentence, java.text.Normalizer.Form.NFKC).strip()
                .replaceFirst("^[-*•]\\s*", "");
        statement = java.text.Normalizer.normalize(statement, java.text.Normalizer.Form.NFKC);
        if (actual.matches("(?s).*(?:如果|假如|若以后|未来|例如|示例|引用|另一|其他研究|其他实验|甲研究|乙研究|并非|不是|无需|不必).*")) return false;
        if (statement.matches("^(?:这是尚未实施的研究|当前只是拟定研究,尚未实施)[。；;]?$")) {
            return actual.matches("^(?:本研究|本次研究|当前|目前|这项研究)(?:仅|只是|仅为|为|只是拟定研究,|仅为拟定研究,)*"
                    + "(?:尚未实施|拟定研究,尚未实施)(?:[。;,].*)?$");
        }
        if (statement.matches("^我没有额外真实数据[。；;]?$")) {
            return actual.matches("^(?:用户|我|我们)没有额外真实数据[。；;]?$" )
                    || actual.matches("^(?:本交付物|当前|本研究)[^。;]*,用户没有额外真实数据[。;]?$" )
                    || actual.matches("^(?:当前|目前)(?:只是|仅为)拟定研究,尚未实施,没有额外真实数据[。;]?$" );
        }
        // “用户未确认任何既有参数”只覆盖同一全称未确认状态，不确认方法、年份或其他具名参数。
        if (statement.matches("^不确认任何既有参数[。；;]?$")) {
            return actual.matches("^用户未确认任何既有参数(?:,(?:已|并)明确委托(?:根据原始研究目标)?提出可开展的推荐设计并说明适用条件)?[。;]?$" );
        }
        // 已委托事项是拟议设计；必须同时包含可开展主方案与适用条件，泛称“提供建议”不足以覆盖。
        if (subject.equals("委托要求") && statement.matches("^请根据原始研究目标提出可开展的推荐设计并说明适用条件[。；;]?$")) {
            return actual.matches("^对以下已委托设计的未决事项,提出可直接开展的拟议主方案并说明适用条件(?:,标注为拟议设计而非已确认事实)?[。;:]?$" )
                    || actual.matches("^撰写研究设计的方法部分,围绕[^。;]+这一主题,提出可开展的推荐设计并说明适用条件[。;]?$" )
                    || actual.matches("^用户(?:未确认任何既有参数,)?已明确委托提出可开展的推荐设计并说明适用条件[。;]?$" );
        }
        return false;
    }

    /**
     * 研究写作的动作、交付物、语言、受众和主题可分配到责任段；每一项均须有当前完整表达。
     * 仅处理无额外条件的完整引导句，未识别的对象、条件或交付类型继续保留原句兜底。
     */
    private static java.util.Optional<List<String>> researchIntroductionClauses(Map<PromptSectionType, PromptSection> sections, String rule) {
        var source = Pattern.compile("^为([^，,。；;]{1,40})撰写(中文|英文)(研究设计的?方法部分)[，,]研究([^，,。；;]{2,120})[。.]?$")
                .matcher(Normalizer.normalize(rule, Normalizer.Form.NFKC).strip());
        if (!source.matches()) return java.util.Optional.empty();
        String audience = Pattern.quote(canonical(source.group(1)));
        String language = Pattern.quote(source.group(2));
        String delivery = Pattern.quote(canonical(source.group(3)));
        String topic = Pattern.quote(canonical(source.group(4)));
        var background = executionSentences(sections, List.of(PromptSectionType.BACKGROUND, PromptSectionType.TASK));
        var execution = executionSentences(sections, List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT));
        // 受众必须明确是读者；研究对象、同词提及、其他研究及未来计划不能提供该来源。
        boolean reader = background.stream().anyMatch(value -> Pattern.compile("面向" + audience + "(?:读者)?[。.]?$").matcher(value).find()
                || Pattern.compile("^(?:本交付物|本交付)?为" + audience + "(?:撰写|编写|起草)").matcher(value).find());
        boolean researchTopic = background.stream().anyMatch(value -> Pattern.compile("(?:^|[，,])(?:本(?:次)?研究|研究)(?:主题|问题)(?:为|是|[：:])"
                + topic + "[。.]?$").matcher(value).find());
        boolean deliverable = execution.stream().anyMatch(value -> Pattern.compile("(?:撰写|编写|起草|交付|输出)(?:该|本)?(?:中文|英文)?"
                + delivery + "(?:正文)?(?:需覆盖.*|正文要求.*|[，,。；;：:].*|$)").matcher(value).find());
        boolean writtenLanguage = execution.stream().anyMatch(value -> Pattern.compile("(?:撰写|编写|起草|交付|输出)" + language
                + delivery + "(?:正文)?(?:[，,。；;：:].*|$)").matcher(value).find());
        if (!researchTopic || !deliverable || !writtenLanguage) return java.util.Optional.empty();
        // 动作、主题和交付已完整覆盖但受众角色含糊时，仅补来源中缺少的读者定位，不重抄整段任务。
        return java.util.Optional.of(reader ? List.of() : List.of("面向" + source.group(1) + "读者。"));
    }

    /** 仅当前责任段中的肯定表达可组合证明任务覆盖；不跨示例、引用、未来或否定表达拼接来源。 */
    private static List<String> executionSentences(Map<PromptSectionType, PromptSection> sections,
            List<PromptSectionType> types) {
        return types.stream().filter(sections::containsKey).flatMap(type -> currentSentences(sections.get(type).content()).stream())
                .filter(value -> !CONDITIONAL.matcher(value).find())
                .filter(value -> !value.matches("(?s).*(?:如果|假如|未来|以后|示例|例子|例如|引用|原文|另一|其他研究|甲研究|乙研究|不得|禁止|不必|无需|不应|不能|不要|不允许|不需要|不要求|不撰写|不交付|不输出).*"))
                .map(value -> Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                        .replaceFirst("^(?:[-*•]\\s*|\\d+[.)、]\\s*)", "")
                        .replaceAll("(?<=[\\p{IsHan}])\\s+|\\s+(?=[\\p{IsHan}])", "")).toList();
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
                .replaceFirst("^设计必须保留[：:]", "保留")
                .replaceAll("(\\d+)\\s*类任务(?:、|[，,])每类\\s*(\\d+)\\s*个(?:、|[，,])共\\s*(\\d+)\\s*个任务", "$1类任务,每类$2个,共$3个任务")
                .replaceAll("比较直接增强(?:和|与)\\s*Plan(?:\\s*两种模式)?", "比较直接增强与Plan")
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
