package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.EffectiveTaskView.ExecutionRequirement;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
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
    private static final String SCORING_SCALE = "(?:评分尺度|评分量表|评分范围|评分量尺|评分形式)";
    private static final String DELEGATION_STATEMENT = "请根据原始研究目标提出可开展的推荐设计并说明适用条件";

    private PlanDecisionIntegrator() { }

    /** 同一确认在四要素正文内有权威表达即视为覆盖；示例和验收提及不能代替执行要求。 */
    static void integrate(Map<PromptSectionType, PromptSection> sections, EffectiveTaskView task) {
        List<String> boundStatements = taskBoundResearchStatements(sections, task);
        for (ExecutionRequirement requirement : task.executionRequirements()) {
            List<String> missing = missingStatements(sections, requirement, boundStatements);
            if (missing.isEmpty()) continue;
            PromptSection current = sections.get(requirement.destination());
            // 多项回答分别核对；只补缺项，不因一个子项缺失就复制整份答案和已落实的要求。
            sections.put(current.type(), new PromptSection(current.type(), current.title(),
                    current.content() + missing.stream().map(value -> "\n- " + value)
                            .collect(java.util.stream.Collectors.joining())));
        }
    }

    /**
     * 修正已完整委托且模型自身要求提出拟议方案的清单标签。
     * 清单必须逐项绑定当前实际题答；部分授权、未来许可或用户保留选择不作整段替换。
     */
    static void reconcileDelegatedStatus(Map<PromptSectionType, PromptSection> sections, EffectiveTaskView task) {
        String raw = task.effectivePrompt();
        if (currentSentences(raw).stream().anyMatch(value -> value.matches(
                "(?s).*(?:由我决定|由用户决定|先让我确认|确认后再|以后确认|以后批准|仅限|不允许推荐|不得提出).*")
                || selectedScoringValue(value))) return;
        List<String> questions = new java.util.ArrayList<>();
        task.executionRequirements().stream().filter(requirement -> requirement.subject().equals("委托要求")
                && canonical(requirement.statement()).equals("请根据原始研究目标提出可开展的推荐设计并说明适用条件"))
                .map(ExecutionRequirement::question).forEach(questions::add);
        var pairs = Pattern.compile("(?m)^问题[：:]([^\\r\\n]+)\\r?\\n回答[：:]([^\\r\\n]+)").matcher(raw);
        while (pairs.find()) {
            if (currentSentences(pairs.group(2)).stream().anyMatch(value ->
                    canonical(value).equals("请根据原始研究目标提出可开展的推荐设计并说明适用条件"))) {
                questions.add(pairs.group(1));
            }
        }
        if (questions.isEmpty()) return;
        List<String> labels = questions.stream().flatMap(question -> delegatedStatusLabels(question).stream())
                .distinct().sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList();
        var statement = Pattern.compile("用户已明确保留的设计参数包括[：:]([^。\\n]+)[；;]这些均未确认，需在方法部分以拟议设计形式提出并说明适用条件。");
        sections.replaceAll((type, section) -> {
            var matcher = statement.matcher(section.content());
            StringBuilder replaced = new StringBuilder();
            while (matcher.find()) {
                String remaining = canonical(matcher.group(1));
                for (String label : labels) remaining = remaining.replace(label, "");
                String replacement = remaining.replaceAll("[、\\s]+", "").isEmpty()
                        ? matcher.group().replace("用户已明确保留的设计参数包括", "已委托提出拟议设计、实际采用参数尚未确认的事项包括")
                        : matcher.group();
                matcher.appendReplacement(replaced, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(replaced);
            return new PromptSection(type, section.title(), replaced.toString());
        });
    }

    /** 标签只来自完整原题的有限同属性别名，不放宽独立对象、数值或条件。 */
    private static List<String> delegatedStatusLabels(String question) {
        var field = delegatedDesignField(question);
        List<String> labels = new java.util.ArrayList<>();
        if (field != null) labels.add(canonical(field.label()));
        if (question.equals("Plan组的问答交互在什么条件下结束？")) labels.add(canonical("Plan组问答交互的终止条件"));
        if (question.equals("同信息公平对照的两组，确认信息以什么形式提供给模型？"))
            labels.add(canonical("同信息对照中确认信息的提供形式"));
        if (question.equals("准确性、完整性、清晰度与可执行性四个维度，评分采用什么量尺？"))
            labels.add(canonical("四个评价维度的评分量尺"));
        if (question.matches("^正文[0-9]+至[0-9]+个汉字是否包含标题、摘要与关键词[？?]$"))
            labels.add(canonical(question));
        return labels;
    }

    /** 完整答案子句可自然加入正文；裸取值另需完整对象，避免把甲机构的同值当成乙机构确认。 */
    static boolean covered(Map<PromptSectionType, PromptSection> sections, ExecutionRequirement requirement) {
        return !requirement.statement().isBlank() && missingStatements(sections, requirement).isEmpty();
    }

    /** 强边界子句可分别落实；带条件或限定对象的回答保持整体，不能拆掉条件后误认覆盖。 */
    private static List<String> missingStatements(Map<PromptSectionType, PromptSection> sections,
            ExecutionRequirement requirement) {
        return missingStatements(sections, requirement, List.of());
    }

    /** 绑定来源的等价句仅用于本次覆盖检查，不写回正文、不修改题答或来源记录。 */
    private static List<String> missingStatements(Map<PromptSectionType, PromptSection> sections,
            ExecutionRequirement requirement, List<String> boundStatements) {
        if (genericDelegation(requirement)
                && delegatedQuestionCovered(sections, requirement.question())) return List.of();
        List<String> statements = executableClauses(requirement);
        List<String> body = sections.entrySet().stream()
                .filter(entry -> entry.getKey() == PromptSectionType.BACKGROUND || entry.getKey() == PromptSectionType.TASK
                        || entry.getKey() == PromptSectionType.OUTPUT || entry.getKey() == PromptSectionType.CONSTRAINTS)
                .flatMap(entry -> (genericDelegation(requirement)
                        ? delegationSentences(entry.getValue().content()) : currentSentences(entry.getValue().content())).stream())
                .toList();
        return statements.stream().filter(statement -> java.util.stream.Stream.concat(body.stream(), boundStatements.stream())
                        .noneMatch(sentence -> covers(sentence, statement, requirement)))
                .toList();
    }

    /**
     * 仅在唯一用户研究主题逐字相同、状态与完整委托均有适用表达时识别已知等价句。
     * 整行限定先于分句过滤，避免分号、句号把后置例外或其他研究的作用域擦掉。
     */
    private static List<String> taskBoundResearchStatements(Map<PromptSectionType, PromptSection> sections,
            EffectiveTaskView task) {
        var topics = task.meaning().anchors().stream()
                .filter(anchor -> anchor.role() == com.promptoptimizer.provider.domain.TaskMeaningContract.Role.RESEARCH_TOPIC)
                .map(anchor -> Normalizer.normalize(anchor.text(), Normalizer.Form.NFKC)).distinct().toList();
        if (topics.size() != 1) return List.of();
        String topic = Pattern.quote(topics.getFirst());
        String introduction = "^(?:本研究主题为|研究主题为)" + topic + "[,;]";
        String directive = "^撰写(?:中文)?研究设计的方法部分,围绕" + topic
                + "(?:这一主题,)?提出可开展的推荐设计,?并说明适用条件[。;]?$";
        var proofs = new java.util.LinkedHashSet<String>();
        for (var entry : sections.entrySet()) {
            if (!List.of(PromptSectionType.BACKGROUND, PromptSectionType.TASK,
                    PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS).contains(entry.getKey())) continue;
            String content = entry.getValue().content();
            if (content.lines().anyMatch(line -> line.strip().matches(
                    "^(?:[-*•]\\s*)?(?:但|仅限|仅就|除非|除外|例外|以上仅|上述仅).*"))) continue;
            // 后续主题声明可能改变跨句／跨行的“当前”指代；有另一主题时不借用整段状态。
            var declarations = Pattern.compile("(?:本研究主题为|研究主题为)([^,;。\\r\\n]+)")
                    .matcher(Normalizer.normalize(String.join("\n", currentSentences(content)), Normalizer.Form.NFKC));
            boolean foreignTopic = false;
            while (declarations.find()) if (!declarations.group(1).strip().equals(topics.getFirst())) foreignTopic = true;
            if (foreignTopic) continue;
            // 原行必须无引用、条件、否定或例外；当前语句过滤继续负责围栏、表格与跨行外部作用域。
            var eligible = content.lines().filter(line -> !line.startsWith("    ") && !line.startsWith("\t"))
                    .map(line -> Normalizer.normalize(line, Normalizer.Form.NFKC))
                    .filter(line -> !line.matches("(?s).*(?:如果|假如|若以后|未来|以后|仅限|仅就|但|除非|除外|例外|例如|示例|引用|原文|不是|并非|无需|不必|[<>`\"“”‘’]).*"))
                    .filter(line -> !line.contains("研究主题为") || Pattern.compile(introduction).matcher(
                            line.strip().replaceFirst("^[-*•]\\s*", "")).find()
                            || currentSentences(line).stream().anyMatch(sentence -> Pattern.compile(introduction)
                                .matcher(sentence.strip()).find()))
                    .flatMap(line -> currentSentences(line).stream())
                    .map(sentence -> sentence.strip().replaceFirst("^[-*•]\\s*", ""))
                    .collect(java.util.stream.Collectors.toSet());
            for (String sentence : currentSentences(content)) {
                String value = Normalizer.normalize(sentence, Normalizer.Form.NFKC).strip().replaceFirst("^[-*•]\\s*", "");
                if (!eligible.contains(value)) continue;
                String state = value.replaceFirst(introduction, "");
                if (state.matches("^(?:当前|目前)(?:仅为|只是)拟定研究,尚未实施,(?:也)?没有额外真实数据[。;]?$")) {
                    proofs.add("当前只是拟定研究,尚未实施");
                    proofs.add("我没有额外真实数据");
                }
                if (value.matches(directive)) proofs.add(DELEGATION_STATEMENT);
            }
        }
        return List.copyOf(proofs);
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
        // 不能靠子串把“仅限甲院／以后再做”或引用抹成全称委托；正常肯定任务前缀仍沿用完整值覆盖。
        if (genericDelegation(requirement) && sentence.matches(
                "(?s).*(?:仅限|以后|未来|但|例外|除外|除非|引用|原文|示例|例如|另一研究|其他研究|不必|无需|[<>`\"“”‘’]).*")) return false;
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

    /** 共享当前执行语句的作用域过滤；引用、表格、代码或其他研究不能证明覆盖或授权。 */
    static List<String> currentSentences(String content) {
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
        if (CONDITIONAL.matcher(conditionText(actual)).find()
                || actual.matches("(?s).*(?:如果|假如|若以后|未来|例如|示例|引用|另一|其他研究|其他实验|甲研究|乙研究|并非|不是|无需|不必|仅限|仅就|除非|除外|例外).*")) return false;
        if (statement.matches("^(?:这是尚未实施的研究|当前只是拟定研究,尚未实施)[。；;]?$")) {
            return actual.matches("^(?:本研究|本次研究|当前|目前|这项研究)(?:仅|只是|仅为|为|只是拟定研究,|仅为拟定研究,)*"
                    + "(?:尚未实施|拟定研究,尚未实施)(?:[。;,].*)?$");
        }
        if (statement.matches("^我没有额外真实数据[。；;]?$")) {
            // 220把完整资料状态与全称未确认状态并列。只接受这两个已验证状态，不能任意拆逗号后忽略例外或局部对象。
            String unconfirmed = "(?:,(?:不确认任何既有参数|用户未确认任何既有参数))?[。;]?$";
            return actual.matches("^(?:用户|我|我们)没有额外真实数据" + unconfirmed)
                    || actual.matches("^(?:本交付物|当前|本研究)[^。;]*,用户没有额外真实数据" + unconfirmed)
                    || actual.matches("^(?:当前|目前)(?:只是|仅为)拟定研究,尚未实施,没有额外真实数据" + unconfirmed);
        }
        // “用户未确认任何既有参数”只覆盖同一全称未确认状态，不确认方法、年份或其他具名参数。
        if (statement.matches("^不确认任何既有参数[。；;]?$")) {
            return actual.matches("^用户未确认任何既有参数(?:,(?:已|并)明确委托(?:根据原始研究目标)?提出可开展的推荐设计并说明适用条件)?[。;]?$" );
        }
        // 已委托事项是拟议设计；必须同时包含可开展主方案与适用条件，泛称“提供建议”不足以覆盖。
        if (subject.equals("委托要求") && statement.matches("^请根据原始研究目标提出可开展的推荐设计并说明适用条件[。；;]?$")) {
            return currentDelegationDirective(actual) || delegatedDesignHeader(actual)
                    || actual.matches("^对以下已委托设计的未决事项,提出可直接开展的拟议主方案并说明适用条件(?:,标注为拟议设计而非已确认事实)?[。;:]?$" )
                    || actual.matches("^撰写研究设计的方法部分,围绕[^。;]+这一主题,提出可开展的推荐设计并说明适用条件[。;]?$" )
                    || actual.matches("^用户(?:未确认任何既有参数,)?已明确委托提出可开展的推荐设计并说明适用条件[。;]?$" );
        }
        return false;
    }

    /** 禁假设数据是独立禁令，不是前文当前状态的假设分支；真正的条件及例外保持原样。 */
    private static String conditionText(String text) {
        return text.replaceAll("((?:不得|禁止|不能|不允许|不要)(?:使用|编造))假设(?=数据|样本|结果)", "$1模拟");
    }

    /**
     * 将当前完整委托落实为任务中的拟议设计义务，不把“实际参数未确认”表达成等待用户才能设计。
     * 只转换绑定原题且无新增解释的提醒，保留完整对象及来源；未识别、条件或局部授权继续走原提醒。
     */
    static void promoteDelegatedPrerequisites(Map<PromptSectionType, PromptSection> sections,
            List<String> prerequisites, EffectiveTaskView task) {
        var fields = new java.util.LinkedHashSet<String>();
        for (var pending : task.pendingDecisions()) {
            if (pending.questionId() == null || !prerequisites.contains(
                    "已委托提出拟议设计，实际采用参数仍未确认：" + pending.question())) continue;
            boolean delegated = task.executionRequirements().stream().anyMatch(requirement ->
                    java.util.Objects.equals(requirement.questionId(), pending.questionId())
                            && requirement.subject().equals("委托要求")
                            && canonical(requirement.statement()).equals("请根据原始研究目标提出可开展的推荐设计并说明适用条件"));
            boolean unconfirmed = task.executionRequirements().stream().anyMatch(requirement ->
                    java.util.Objects.equals(requirement.questionId(), pending.questionId())
                            && requirement.subject().equals("未确认边界") && coveredRule(sections, requirement.statement()));
            var field = delegatedDesignField(pending.question());
            if (delegated && unconfirmed && field != null && !delegatedQuestionCovered(sections, pending.question())) {
                fields.add(field.label());
            }
        }
        if (fields.isEmpty()) return;
        appendCurrentTask(sections, "对以下已委托设计的子项给出拟议主方案及适用条件，标明属于拟议设计，不冒充已确认或已实施："
                + String.join("；", fields) + "。");
    }

    /**
     * 只补当前研究评价中已声明但未落入正文的评分义务，不复制元数据中的具体方案或默认数值。
     * 原要求须有全部四维评价与分歧处理、当前完整委托，并且模型声明对应补全；已选尺度或禁止推荐保持原样。
     */
    static void completeDeclaredEvaluation(Map<PromptSectionType, PromptSection> sections, EffectiveTaskView task,
            com.promptoptimizer.provider.domain.TaskEnhancementAssessment assessment) {
        if (assessment == null || task.intent().deliveryProfile() != TaskDeliveryProfile.ACADEMIC_METHODS) return;
        boolean boundDelegation = task.executionRequirements().stream().anyMatch(requirement -> requirement.subject().equals("委托要求")
                    && requirement.questionId() != null
                    && canonical(requirement.statement()).equals("请根据原始研究目标提出可开展的推荐设计并说明适用条件"));
        var original = currentSentences(task.effectivePrompt());
        // 直接增强不伪造题ID；当前原始指令中的完整设计委托也可授权，引用、未来和局部委托不算。
        boolean originalDelegation = original.stream().map(PlanDecisionIntegrator::canonical).anyMatch(value ->
                value.equals("请根据原始研究目标提出可开展的推荐设计并说明适用条件"));
        if (!boundDelegation && !originalDelegation) return;
        boolean requested = original.stream().anyMatch(value -> value.contains("准确性") && value.contains("完整性")
                && value.contains("清晰度") && value.contains("可执行性") && value.contains("评价") && value.contains("分歧处理")
                && !CONDITIONAL.matcher(value).find() && !value.matches("(?s).*(?:例如|示例|引用|另一研究|其他研究).*"));
        boolean reserved = original.stream().anyMatch(value ->
                value.matches("(?s).*(?:不得|禁止|不要|无需|不必|不提供|不提出|不推荐|不设计)[^。;]*(?:评分|量表|操作定义).*" )
                || value.matches("(?s).*(?:评分|量表|操作定义)[^。;]*(?:不提供|不提出|不推荐|不设计|确认后再|以后再|仅供参考).*" )
                || selectedScoringValue(value))
                || original.stream().anyMatch(value -> value.matches("(?s).*(?:评分|量表|量尺)[^。;]*(?:由我决定|由用户决定|以后确认|确认后再).*"))
                || original.stream().anyMatch(value -> value.matches("(?s).*(?:以后|未来|确认后再)[^。;]*" + SCORING_SCALE + ".*"))
                || task.executionRequirements().stream().anyMatch(requirement -> !requirement.subject().equals("委托要求")
                    && requirement.question().matches("(?s).*(?:评分|量表|量尺).*" )
                    && !List.of("用户说明", "未确认边界").contains(requirement.subject()));
        boolean declared = assessment.gaps().stream().anyMatch(PlanDecisionIntegrator::currentScoringDeclaration);
        if (!requested || reserved || !declared) return;
        boolean covered = List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT).stream()
                .filter(sections::containsKey).flatMap(type -> currentSentences(sections.get(type).content()).stream())
                .anyMatch(value -> value.contains("准确性") && value.contains("完整性") && value.contains("清晰度")
                    && value.contains("可执行性") && value.matches("(?s).*" + SCORING_SCALE + ".*" )
                    && value.matches("(?s).*(?:等级描述|评分锚点).*" ) && value.contains("分歧")
                    && value.matches("(?s).*(?:提出|给出|明确|定义).*" ) && !CONDITIONAL.matcher(value).find()
                    && !value.matches("(?s).*(?:待确认后|以后|未来|不得|禁止|不要|另一研究|其他研究|示例|引用).*"));
        if (covered) return;
        appendCurrentTask(sections, "针对原要求的准确性、完整性、清晰度与可执行性，提出拟议评分尺度、可操作定义及各等级评分锚点、与尺度相配的分歧处理规则，并说明适用条件。");
    }

    /** 询问“评分采用什么量尺”不是已选量尺；只把当前陈述中的选定值作为保留依据。 */
    private static boolean selectedScoringValue(String value) {
        if (value.matches("(?s).*(?:什么|哪种|哪个|哪些|是否|[?？]).*")) return false;
        if (value.strip().matches("^(?:本研究(?:的)?)?" + SCORING_SCALE + "(?:为|是)[0-9]+(?:至|到|[-–—])[0-9]+分[。.]?$")) return true;
        return value.matches("(?s).*(?:采用|使用|已确认)[^。;]*(?:量表|量尺|" + SCORING_SCALE + ").*" )
                || value.matches("(?s).*(?:" + SCORING_SCALE + "|量表|量尺)[^。;]*(?:采用|使用|已确认|已确定).*" );
    }

    /** 评分量尺与尺度是同一属性；只承接当前补全声明，不从元数据借用外部研究或未来授权。 */
    private static boolean currentScoringDeclaration(com.promptoptimizer.provider.domain.TaskEnhancementAssessment.Gap gap) {
        if (gap.kind() != com.promptoptimizer.provider.domain.TaskEnhancementAssessment.Kind.PROFESSIONAL_OPTION
                && gap.kind() != com.promptoptimizer.provider.domain.TaskEnhancementAssessment.Kind.EXECUTION_STEP
                && !currentScoringQualityCheck(gap)) return false;
        String missing = String.join("。", currentSentences(gap.missing()));
        String completion = String.join("。", currentSentences(gap.completion()));
        String scope = missing + completion;
        if (!scope.matches("(?s).*" + SCORING_SCALE + ".*")
                || scope.matches("(?s).*(?:另一(?:项)?研究|其他研究|甲研究|乙研究|示例|引用|以后确认后再|确认后再|以后再).*")) return false;
        if (completion.matches("(?s).*(?:不得|禁止|不要|无需|不必|不提供|不提出|不推荐|不设计)[^。;]*(?:评分|量表|量尺).*")) return false;
        // 保留既有声明路径：量表锚点中的“1=…”不是新增的条件授权，也不会被复制成默认参数。
        if (completion.matches("(?s).*(?:提出|给出)[^。;]*(?:评分|评价).*")) return true;
        if (!CONDITIONAL.matcher(completion).find()
                && declaredScoringCompletion(missing, completion)) return true;
        // “上述各项”仅接当前完整四维设计清单，仍由上层核对原始委托和保留选择；不抄其候选数值。
        return missing.contains("四个评价维度") && gap.basis().contains("委托设计而非用户保留选择")
                && canonical(completion).equals("在方法部分以拟议设计形式给出上述各项的主方案与适用条件明确标注为推荐而非已确认或已实施");
    }

    /** 合法QUALITY_CHECK也可声明评分设计缺口；只接当前四维评价，不借普通校对、其他研究或未来授权补方案。 */
    private static boolean currentScoringQualityCheck(com.promptoptimizer.provider.domain.TaskEnhancementAssessment.Gap gap) {
        if (gap.kind() != com.promptoptimizer.provider.domain.TaskEnhancementAssessment.Kind.QUALITY_CHECK) return false;
        String completion = String.join("。", currentSentences(gap.completion()));
        String scope = gap.missing() + completion + gap.basis();
        // 新兼容分支比枚举标签更看重完整声明，仍由上层核对原始委托、四维目标和用户保留选择。
        return scope.contains("准确性") && scope.contains("完整性") && scope.contains("清晰度")
                && scope.contains("可执行性")
                && !scope.matches("(?s).*(?:另一(?:项)?研究|其他研究|甲研究|乙研究|示例|引用).*" )
                && declaredScoringCompletion(gap.missing(), completion)
                && !CONDITIONAL.matcher(completion).find()
                && !completion.matches("(?s).*(?:不得|禁止|不要|无需|不必|不提供|不提出|不推荐|不设计)[^。;]*(?:评分|量表|操作定义).*" )
                && !completion.matches("(?s).*(?:以后确认后|确认后再|另行批准后|以后再|待[^。;]{0,20}确认后)[^。;]*(?:评分|量表|操作定义).*" );
    }

    /** 评分属性可位于动作之前；列表的拟议主方案只承接已具名评分项，不抄元数据的候选数值。 */
    private static boolean declaredScoringCompletion(String missing, String completion) {
        return completion.matches("(?s).*(?:提出|给出)[^。;]*(?:评分|评价).*")
                || completion.matches("(?s).*(?:评分|评价)[^。;]*(?:提出|给出).*")
                || missing.matches("(?s).*" + SCORING_SCALE + ".*")
                    && completion.matches("(?s).*(?:提出|给出)[^。;]*拟议主方案.*")
                    && completion.contains("适用条件")
                || declaredScoringSupplement(missing, completion);
    }

    /** 只承接当前四维设计清单中的“补充”动作；元数据不产生量表取值、用户许可或未来授权。 */
    private static boolean declaredScoringSupplement(String missing, String completion) {
        if (!List.of("准确性", "完整性", "清晰度", "可执行性", "分歧").stream().allMatch(missing::contains)) return false;
        String text = Normalizer.normalize(completion, Normalizer.Form.NFKC).strip();
        if (!text.matches("(?s)^在(?:TASK|任务段落|任务部分)中补充:.*")
                || CONDITIONAL.matcher(text).find()
                || text.matches("(?s).*(?:如果|假如|若|未来|以后|但|仅限|除非|例外|除外|由用户决定|由我决定|待确认|尚未确定|[<>`\"“”‘’]).*")) return false;
        String items = text.replaceFirst("^在(?:TASK|任务段落|任务部分)中补充:", "");
        return currentSentences(items).stream().map(String::strip).anyMatch(value -> value.matches(
                "^四(?:项|个)(?:评价)?指标各自的操作定义(?:与|及)" + SCORING_SCALE + "建议[。;]?$"));
    }

    /**
     * 全部追加项具有相同且已绑定的当前委托状态时，仅把共同前缀移到标题。
     * 每个原题逐字保留；局部、未来、混合状态及无来源提醒不参与合并。
     */
    static java.util.Optional<List<String>> commonDelegatedQuestions(List<String> prerequisites, EffectiveTaskView task) {
        String prefix = "已委托提出拟议设计，实际采用参数仍未确认：";
        if (prerequisites.size() < 2) return java.util.Optional.empty();
        var questions = new java.util.ArrayList<String>();
        for (String value : prerequisites) {
            if (!value.startsWith(prefix)) return java.util.Optional.empty();
            String question = value.substring(prefix.length());
            boolean bound = task.pendingDecisions().stream().anyMatch(pending ->
                    pending.questionId() != null && pending.question().equals(question)
                            && task.executionRequirements().stream().anyMatch(requirement ->
                                pending.questionId().equals(requirement.questionId())
                                    && requirement.subject().equals("委托要求")
                                    && canonical(requirement.statement()).equals("请根据原始研究目标提出可开展的推荐设计并说明适用条件"))
                            && task.executionRequirements().stream().anyMatch(requirement ->
                                pending.questionId().equals(requirement.questionId())
                                    && requirement.subject().equals("未确认边界")
                                    && canonical(requirement.statement()).equals("不确认任何既有参数")));
            if (!bound) return java.util.Optional.empty();
            questions.add(question);
        }
        return java.util.Optional.of(List.copyOf(questions));
    }

    /** 追加仍属于当前任务；已有其他研究或示例标题不能把新增执行要求收进其资料作用域。 */
    private static void appendCurrentTask(Map<PromptSectionType, PromptSection> sections, String requirement) {
        PromptSection current = sections.get(PromptSectionType.TASK);
        boolean foreign = false;
        for (String line : current.content().lines().toList()) {
            String heading = line.strip().replaceFirst("^#{1,6}\\s*", "").replaceFirst("^\\*\\*(.+?)\\*\\*$", "$1");
            if (heading.matches("(?:另一(?:项)?研究|其他研究|甲研究|乙研究|示例|例子|引用|原文|未来方案|以后确认)[：:]?")) foreign = true;
            else if (heading.matches("(?:本研究|本次研究|当前研究|本任务|本次任务)[：:]?")) foreign = false;
        }
        sections.put(current.type(), new PromptSection(current.type(), current.title(), current.content()
                + (foreign ? "\n本任务：\n" : "\n") + requirement));
    }

    /**
     * 只省略已完整落入执行正文的委托提醒；原题、全部来源及实际参数未确认状态仍在任务视图和API中。
     * 新解释、未来确认、独立对象及尚无显式未确认边界的题继续保留原提醒。
     */
    static List<String> uncoveredDelegatedPrerequisites(Map<PromptSectionType, PromptSection> sections,
            List<String> prerequisites, EffectiveTaskView task) {
        String prefix = "已委托提出拟议设计，实际采用参数仍未确认：";
        // 复用已有执行投影里的绑定ID，不为每条提醒重建模型输入和边界契约。
        var delegatedIds = task.executionRequirements().stream().filter(requirement -> requirement.subject().equals("委托要求"))
                .map(ExecutionRequirement::questionId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return prerequisites.stream().filter(value -> task.pendingDecisions().stream().noneMatch(pending ->
                pending.questionId() != null && delegatedIds.contains(pending.questionId()) && value.equals(prefix + pending.question())
                        && task.executionRequirements().stream().anyMatch(requirement ->
                            java.util.Objects.equals(requirement.questionId(), pending.questionId())
                                    && requirement.subject().equals("未确认边界")
                                    && coveredRule(sections, requirement.statement()))
                        && delegatedQuestionCovered(sections, pending.question()))).toList();
    }

    /** 当前委托列表须有拟议主方案及适用条件；题目对象和属性只能从完整原题的有限名词化语法取得。 */
    private static boolean delegatedQuestionCovered(Map<PromptSectionType, PromptSection> sections, String question) {
        var field = delegatedField(question);
        if (field == null) return false;
        for (PromptSectionType type : List.of(PromptSectionType.TASK, PromptSectionType.OUTPUT)) {
            if (!sections.containsKey(type)) continue;
            var design = delegatedDesignField(question);
            if (design != null && naturalDelegatedRequirementCovered(sections.get(type).content(), design)) return true;
            StringBuilder list = null;
            for (String sentence : currentSentences(sections.get(type).content())) {
                String text = Normalizer.normalize(sentence, Normalizer.Form.NFKC).strip()
                        .replaceFirst("^[-*•]\\s*", "");
                if (CONDITIONAL.matcher(conditionText(text)).find()
                        || text.matches("(?s).*(?:未来|以后|例如|示例|引用|另一研究|其他研究|甲研究|乙研究|仅限|除非|例外).*")) {
                    list = null;
                    continue;
                }
                // 不借普通提及、否定动作或局部对象列表消除当前委托；列表头明确覆盖其后完整子项。
                boolean header = delegatedDesignHeader(text);
                if (header) list = new StringBuilder(text.substring(text.indexOf(':') + 1));
                else if (list != null && !text.matches("(?s).*(?:不得|禁止|不能|不应|无需|不必|不允许|不要)(?:提出|给出|提供).*")) {
                    list.append(';').append(text);
                } else list = null;
                // 先核完整列表的后续限定；第一子项命中不能忽略分号之后的新条件或例外。
                if (text.endsWith("。") || text.endsWith(".")) {
                    if (list != null && field.matcher(list).find()) return true;
                    list = null;
                }
            }
            if (list != null && field.matcher(list).find()) return true;
        }
        return false;
    }

    /** 只有同一完整委托可共用这条覆盖路径，具名参数、部分委托和其他执行要求仍按原规则核对。 */
    private static boolean genericDelegation(ExecutionRequirement requirement) {
        return requirement.subject().equals("委托要求")
                && canonical(requirement.statement()).equals(canonical(DELEGATION_STATEMENT));
    }

    /** 325把当前委托与拟议状态写在同一句；只接完整肯定指令及等价状态说明，不接任意后缀。 */
    private static boolean currentDelegationDirective(String text) {
        String directive = Normalizer.normalize(text, Normalizer.Form.NFKC).strip().replaceFirst("^[-*•]\\s*", "");
        return directive.matches("^" + DELEGATION_STATEMENT
                + "(?:,所有建议(?:均)?(?:标明|标注)为拟议设计,不冒充已确认或已实施)?[。;]?$" );
    }

    /**
     * 委托覆盖不借缩进代码；同一句内分号后的局部限制仍与前文保持同一分析范围。
     * 仅改变覆盖判断的临时视图，不修改正文、字面量或来源记录。
     */
    private static List<String> delegationSentences(String content) {
        var lines = content.lines().filter(line -> !line.startsWith("    ") && !line.startsWith("\t")).toList();
        if (lines.stream().map(String::strip).anyMatch(line -> line.matches(
                "^(?:但|仅限|除非|例外|除外|以后|未来|如果|假如|若).*"))) return List.of();
        // 先按完整句隔离：未来数据分布等独立统计条件不能使前面的当前委托重新变成缺项。
        return currentSentences(lines.stream().map(line -> java.util.Arrays.stream(line.split("(?<=。)"))
                .map(sentence -> sentence.matches("(?s).*" + DELEGATION_STATEMENT
                        + "[^。]*[；;](?:但|仅限|除非|例外|除外|以后|未来).*" )
                        ? sentence.replace('；', '，').replace(';', ',') : sentence)
                .collect(java.util.stream.Collectors.joining()))
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    /**
     * 当前完整委托下的自然执行清单也可覆盖具名子项；不要求模型复述平台固定标题。
     * 按完整列表项检查条件与否定，避免只取分号前半句，或借用其他对象、引用与示例。
     */
    private static boolean naturalDelegatedRequirementCovered(String content, DelegatedDesignField field) {
        var sentences = delegationSentences(content);
        if (sentences.stream().map(value -> Normalizer.normalize(value, Normalizer.Form.NFKC).strip())
                .noneMatch(PlanDecisionIntegrator::currentDelegationDirective)) return false;
        boolean inList = false;
        StringBuilder item = null;
        List<String> items = new java.util.ArrayList<>();
        for (String sentence : sentences) {
            String text = Normalizer.normalize(sentence, Normalizer.Form.NFKC).strip();
            if (text.matches("^(?:需|须|应)在方法部分中落实以下内容:$")) {
                inList = true;
                continue;
            }
            if (!inList || text.isBlank()) continue;
            if (text.matches("^[-*•]\\s+.*")) {
                if (item != null) items.add(item.toString());
                item = new StringBuilder(text.replaceFirst("^[-*•]\\s+", ""));
            } else if (item != null) item.append(';').append(text);
        }
        if (item != null) items.add(item.toString());
        return items.stream().map(PlanDecisionIntegrator::designSpacing).anyMatch(text ->
                !CONDITIONAL.matcher(text).find()
                && !text.matches("(?s).*(?:如果|假如|若|未来|以后|但|仅限|除非|例外|除外|引用|示例|例如|另一研究|其他研究|待确认|尚未确定|未确认"
                    + "|不得|禁止|不能|不应|无需|不必|不允许|不要|不提供|不提出|[<>`\"“”‘’]).*")
                && field.listItemCoverage().matcher(text).find());
    }

    /** 仅规范数字与汉字之间的排版空白；数字之间及ASCII标识符内部的空格继续逐字区分。 */
    private static String designSpacing(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("(?<=\\d)\\s+(?=\\p{IsHan})|(?<=\\p{IsHan})\\s+(?=\\d)", "");
    }

    /** 明确的当前拟议主方案及适用条件是委托执行表达；局部对象前缀、普通建议和否定动作不匹配。 */
    private static boolean delegatedDesignHeader(String text) {
        return text.matches("^(?:对以下已委托设计的(?:子项|未决事项)|由于用户[^。;]+执行者应就以下未确认事项)"
                + ".*(?:给出|提出)(?:可直接开展的|可开展的)?拟议主方案(?:并说明|及)适用条件[^:。;]*:.*$")
                && !text.matches("(?s).*(?:不得|禁止|不能|不应|无需|不必|不允许|不要)(?:提出|给出|提供).*" );
    }

    /**
     * 仅转换完整问句的疑问语法，不删除机构、年份、数字或限定对象；未识别问句不作覆盖判断。
     * “如何取得”与获取流程、“对哪些信息设盲”与设盲范围是属性名词化，不是默认选择具体参数。
     */
    private static Pattern delegatedField(String question) {
        var field = delegatedDesignField(question);
        return field == null ? null : field.coverage();
    }

    /** 有限完整问句的字段名与覆盖模式共享同一解析，不能用简化对象生成宽泛的执行要求。 */
    private static DelegatedDesignField delegatedDesignField(String question) {
        String text = Normalizer.normalize(question, Normalizer.Form.NFKC).strip();
        if (CONDITIONAL.matcher(conditionText(text)).find()
                || text.matches("(?s).*(?:以后|未来|否定|除非|例外|仅限|不得|禁止|不能|不要|不允许|不应|不采用|不是|并非|无需|不必).*")) return null;
        String object = null, attribute = null, label = null;
        var category = Pattern.compile("^(.{2,80}?)具体指哪[^，,。;?]{1,8}类\\?$").matcher(text);
        var source = Pattern.compile("^(.{2,100}?)如何确定\\?是[^?]+还是其他来源\\?$").matcher(text);
        var blind = Pattern.compile("^(.{2,80}?)具体对哪些信息设盲\\?$").matcher(text);
        var acquisition = Pattern.compile("^(.{2,100}?)如何(?:取得|获取)\\?$").matcher(text);
        var termination = Pattern.compile("^(.{2,80}?)在什么条件下结束\\?$").matcher(text);
        var scoring = Pattern.compile("^(准确性、完整性、清晰度与可执行性四个维度)[,，]评分采用什么量尺\\?$").matcher(text);
        var information = Pattern.compile("^(同信息公平对照的两组)[,，]确认信息以什么形式提供给模型\\?$").matcher(text);
        var allocation = Pattern.compile("^([0-9]+个任务在[0-9]+类中)的分配与顺序如何安排\\?$").matcher(text);
        if (category.matches()) { object = category.group(1); attribute = "(?:分类框架|类别划分|具体类别)"; label = "的分类框架"; }
        else if (source.matches()) { object = source.group(1); attribute = "(?:构造来源|来源与选取标准|来源与选取方式)"; label = "的构造来源"; }
        else if (blind.matches()) { object = blind.group(1); attribute = "设盲范围"; label = "的设盲范围"; }
        else if (acquisition.matches()) { object = acquisition.group(1); attribute = "(?:获取|取得)(?:与封存)?流程"; label = "获取流程"; }
        else if (termination.matches()) { object = termination.group(1); attribute = "(?:终止条件|终止规则)"; label = "的终止条件"; }
        else if (scoring.matches()) { object = scoring.group(1); attribute = "评分量尺"; label = "的评分量尺"; }
        else if (information.matches()) { object = information.group(1); attribute = "确认信息提供形式"; label = "的确认信息提供形式"; }
        else if (allocation.matches()) { object = allocation.group(1); attribute = "分配与顺序"; label = "的分配与顺序"; }
        if (object == null) return null;
        // 左边界防止“甲院盲评”借用裸“盲评”的对象；连接词只分隔当前列表的完整并列子项。
        return new DelegatedDesignField(object + label, Pattern.compile("(?:^|[;；，,、\\n]|与|及|和|以及)" + Pattern.quote(object)
                + "(?:的)?" + attribute + "(?=$|[;；，,、\\n。]|与|及|和|以及)"),
                Pattern.compile("^" + Pattern.quote(designSpacing(object)) + "(?:的)?" + attribute
                        + "(?:建议)?(?=$|[;；，,、\\n。:：]|[（(]|与|及|和|以及)"));
    }

    /** 字段名保留来源完整对象，覆盖模式只允许同对象、同属性的句法等价。 */
    private record DelegatedDesignField(String label, Pattern coverage, Pattern listItemCoverage) { }

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
