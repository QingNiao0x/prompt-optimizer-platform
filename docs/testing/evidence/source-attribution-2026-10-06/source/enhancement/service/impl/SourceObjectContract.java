package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将资料中的取值与对象、属性、版本及证据状态一起核对，不以出现次序推断版本归属。
 * 只识别明确的“一版／另一版”资料，不替代模型的跨领域理解或生成缺失的项目事实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class SourceObjectContract {
    enum State { KNOWN_VALUE_UNBOUND_VERSION, EXPLICIT_VERSION }

    /** 对象与属性共同构成范围；条件、原始取值和来源不在归一化中删除。 */
    record Claim(String object, String property, String version, String condition,
                 String value, State state, String source) {
        String subject() { return object + property; }
    }

    private static final Pattern ALTERNATIVES = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9_]{1,18}?)(条款|规则|阈值|条件|标准|指标|口径|职责|范围)"
                    + "(?:的)?(?:一|某一|一个)(?:版本|版)(?:中)?(?:写为|写|规定|要求|是|为|采用)?"
                    + "([^，,。；;\\n]{2,100})[，,](?:而|但)?另一(?:版本|版)"
                    + "(?:中)?(?:写为|写|新增|规定|要求|是|为|采用)?([^。；;\\n]{2,100})");
    private static final Pattern VERSION = Pattern.compile("(?:草稿|版本|方案|文本|文稿|报告)[\\s]*[A-Z甲乙丙丁一二三四0-9]{1,8}"
            + "|[A-Z甲乙丙丁一二三四0-9]{1,8}(?:版本|版)");
    private static final Pattern UNKNOWN = Pattern.compile("未(?:明确|确定|绑定|建立|核实|对应|标明)|尚不清楚|待核实|待确认|对应未知|归属未知|无法对应|对应关系.*未知");
    // 签署、日期或其他状态未知不能撤销版本格已经表达的归属；必须明确是这个对应关系未知。
    private static final Pattern UNBOUND_VERSION = Pattern.compile(
            "版本对应待核实|(?:版本归属|对应关系|版本对应).{0,8}(?:未知|未建立|待核|未确定)|未(?:绑定|指定|对应)|对应未知");
    private static final Pattern CONDITION = Pattern.compile("^((?:在|当|如果|若)[^，,。；;\\n]{1,40}?(?:时|情况下|条件下)[，,:：]?)(.+)$");
    private static final Pattern COMPARISON_ROLE = Pattern.compile("(^|[，,])\\s*(?:以)?(?:" + VERSION.pattern()
            + ")(?:仅)?(?:作为|作|用作)(?:对照版本|对照文本|参照版本|比较版本)(?=$|[，,。；;])");
    private static final int MAX_PLANNING_GUIDANCE_CHARACTERS = 6000;
    private final List<Claim> claims;
    private final List<Evidence> evidence;
    private final Set<String> versions;

    private SourceObjectContract(List<Claim> claims, List<Evidence> evidence, Set<String> versions) {
        this.claims = List.copyOf(claims);
        this.evidence = List.copyOf(evidence);
        this.versions = Set.copyOf(versions);
    }

    /** 使用当前安全资料与仍有效的绑定事实；测试、示例和生成报告不能建立真实业务归属。 */
    static SourceObjectContract from(String raw, ContextSnapshot context, List<PlanningFactCard> facts) {
        return from(raw, context, facts, List.of());
    }

    /** 只有服务端绑定后的明确答案可补充归属，暂不确定与当前情况不能伪造为本次版本选择。 */
    static SourceObjectContract from(String raw, ContextSnapshot context, List<PlanningFactCard> facts,
                                     List<ConfirmedPlanDecision> confirmed) {
        List<Evidence> materials = new ArrayList<>();
        materials.add(new Evidence(raw == null ? "" : raw, "原始需求"));
        var policy = new PlanningEvidencePolicy(raw);
        if (context != null) context.fileSnippets().stream().filter(policy::allows)
                .filter(file -> trusted(PlanningEvidencePolicy.origin(file.path(), file.language())))
                .forEach(file -> materials.add(new Evidence(String.join("。", policy.evidenceLines(file).stream()
                        .filter(policy::relevantBusinessContent).toList()), file.path())));
        if (facts != null) facts.stream().filter(fact -> trusted(fact.origin()))
                .forEach(fact -> materials.add(new Evidence(fact.evidence(), fact.sourcePath())));
        confirmed.stream().filter(decision -> decision.scope() == ConfirmedPlanDecision.Scope.CHOICE
                        || decision.scope() == ConfirmedPlanDecision.Scope.TARGET)
                .filter(decision -> !PlanAnswerSemantics.unresolved(decision.answer()))
                .forEach(decision -> materials.add(new Evidence(decision.answer(), "用户已确认:" + decision.questionId())));
        var versions = new LinkedHashSet<String>();
        var claims = new LinkedHashSet<Claim>();
        var safety = new SensitiveValueDetector();
        for (Evidence material : materials) {
            String text = material.text();
            VERSION.matcher(text).results().map(match -> match.group().replaceAll("\\s", "")).forEach(versions::add);
            var alternatives = ALTERNATIVES.matcher(text);
            while (alternatives.find()) {
                String sentence = alternatives.group();
                if (safety.containsCredential(sentence) || sentence.contains("例如") || sentence.contains("假设")) continue;
                for (int value : List.of(3, 4)) {
                    String statement = alternatives.group(value).strip();
                    var condition = CONDITION.matcher(statement);
                    claims.add(new Claim(alternatives.group(1), alternatives.group(2), "",
                            condition.matches() ? condition.group(1) : "", statement,
                            State.KNOWN_VALUE_UNBOUND_VERSION, material.source()));
                }
            }
        }
        var contract = new SourceObjectContract(List.copyOf(claims), materials, versions);
        var represented = new LinkedHashSet<Claim>(claims);
        for (Claim claim : claims) for (String version : contract.explicitVersions(claim)) {
            represented.add(new Claim(claim.object(), claim.property(), version, claim.condition(), claim.value(),
                    State.EXPLICIT_VERSION, claim.source()));
        }
        return new SourceObjectContract(List.copyOf(represented), materials, versions);
    }

    /** 给生成器和复制正文同一份明确边界；不知道属于哪个版本不等于不知道条款内容。 */
    String guidance() {
        var subjects = new LinkedHashSet<String>();
        claims.stream().filter(claim -> claim.state() == State.KNOWN_VALUE_UNBOUND_VERSION
                && !explicitlyBound(claim)).map(Claim::subject).forEach(subjects::add);
        if (subjects.isEmpty() || versions.isEmpty()) return "";
        return "资料归属边界：" + String.join("、", subjects)
                + "的一版／另一版内容已有依据，但与" + String.join("／", versions)
                + "的完整对应关系未建立。未核实项以“版本对应待核实”标注，保留各项内容；不得按材料顺序绑定版本，也不得拼接为统一规则。"
                + "若表格使用具体版本列，该属性尚未建立对应的单元格只能填写“版本对应待核实”；"
                + "一版／另一版的已知内容另列，不能靠表尾提醒抵消单元格暗示的归属。"
                + "其他已有明确版本依据的属性继续按其证据整理。";
    }

    /** 将未决归属绑定到交付格式；内容出处与版本证据分别判断，不用表尾状态修补错误列。 */
    String deliveryGuidance() {
        if (guidance().isBlank()) return "";
        var grouped = new LinkedHashMap<Claim, LinkedHashSet<String>>();
        for (Claim claim : claims) {
            if (claim.state() != State.KNOWN_VALUE_UNBOUND_VERSION) continue;
            // 同一内容只占一行，多个内容出处并列；条件和原始取值不压缩或改写。
            var identity = new Claim(claim.object(), claim.property(), claim.version(), claim.condition(),
                    claim.value(), claim.state(), "");
            grouped.computeIfAbsent(identity, ignored -> new LinkedHashSet<>()).add(claim.source());
        }
        var rows = new LinkedHashSet<String>();
        for (var entry : grouped.entrySet()) {
            Claim claim = entry.getKey();
            var bound = explicitVersions(claim).stream().sorted().toList();
            String bindingSources = bindingEvidence(claim).stream().map(Binding::source).distinct().sorted()
                    .collect(java.util.stream.Collectors.joining("；"));
            rows.add("| " + tableCell(claim.subject()) + " | " + tableCell(claim.value()) + " | "
                    + (bound.isEmpty() ? "版本对应待核实" : String.join("／", bound)) + " | "
                    + tableCell(String.join("；", entry.getValue())) + " | "
                    + (bindingSources.isEmpty() ? "尚无对应证据" : tableCell(bindingSources)) + " |");
        }
        return "资料归属的交付要求：以下是本次资料的内容与归属视图，内容出处不自动证明版本对应。"
                + "交付时把未绑定内容独立列出，版本归属保持待核实；已明确的其他属性仍按各自证据整理。"
                + "不要为追求A/B并排而按出现顺序填写。保留用户要求的交付形式；若指定A/B列，"
                + "未建立对应的版本格填写“版本对应待核实”，具体内容另列在“未绑定版本的资料内容”中。"
                + "叙述、风险清单和问题列表也不得重新断言未知归属。\n"
                + "| 对象／属性 | 资料明确内容 | 已证实版本或对应状态 | 内容出处 | 归属依据 |\n"
                + "| --- | --- | --- | --- | --- |\n" + String.join("\n", rows);
    }

    /** 翻译的资料事实仍须忠实保留，但不能将待译内容升级为新增的分析交付任务。 */
    String deliveryGuidance(TaskDeliveryProfile profile) {
        return profile == TaskDeliveryProfile.TRANSLATION ? "" : deliveryGuidance();
    }

    /**
     * 提问阶段先给出同一份版本证据视图，减少生成后拒绝；只加入完整行，不发送原文快照。
     * 超出预算的行保持在服务端校验范围内，并明确告知覆盖缺口，不将其默认为未知或已知。
     */
    String planningGuidance(TaskDeliveryProfile profile) {
        String delivery = deliveryGuidance(profile);
        if (delivery.isBlank()) return "";
        var result = new StringBuilder("资料归属的提问依据：表中已证实的版本对应直接继承，不重复询问；"
                + "版本对应待核实的内容不能按顺序或排除法套到其他版本。主文本、对照版本是复核角色，"
                + "不证明具体条款属于哪个版本。提示、所有选项及推荐理由均须遵守，不补全未知归属。\n");
        int omitted = 0;
        for (String line : delivery.substring(delivery.indexOf("| 对象／属性 |")).lines().toList()) {
            if (result.length() + line.length() + 1 <= MAX_PLANNING_GUIDANCE_CHARACTERS - 100) {
                result.append(line).append('\n');
            } else omitted++;
        }
        if (omitted > 0) result.append("另有 ").append(omitted).append(" 行超出提问摘要预算；不得据摘要缺席推断归属。\n");
        return result.toString().strip();
    }

    /** 引用资料值时仅转义表格分隔符和换行，不更改条件、否定或运算符。 */
    private static String tableCell(String value) {
        return value.replace("|", "\\|").replaceAll("\\R", " ").strip();
    }

    /** 只拒绝把未绑定取值断言给具体版本；比较指令、未知标记及其他属性不被拦截。 */
    void validate(String content, String field) {
        if (claims.isEmpty() || content == null) return;
        List<String> columns = List.of();
        for (String original : content.lines().toList()) {
            String line = Normalizer.normalize(original, Normalizer.Form.NFKC).replace("**", "").replace("`", "");
            if (line.strip().startsWith("|")) {
                var cells = java.util.Arrays.asList(line.split("(?<!\\\\)\\|", -1));
                if (cells.stream().allMatch(cell -> cell.isBlank() || cell.strip().matches(":?-+:?"))) continue;
                if (columns.isEmpty()) { columns = cells; continue; }
                validateTable(columns, cells, line, field);
                continue;
            }
            columns = List.of();
            for (String sentence : line.split("[。；;]")) validateSentence(sentence, field);
        }
    }

    /** 横向列与纵向行分别检查，未知标记只作用于当前版本和内容，不借用另一状态格。 */
    private void validateTable(List<String> columns, List<String> cells, String line, String field) {
        for (Claim claim : claims) {
            if (!line.contains(claim.object()) && columns.stream().noneMatch(cell -> cell.contains(claim.object()))) continue;
            for (int column = 0; column < Math.min(columns.size(), cells.size()); column++) {
                String cell = cells.get(column);
                if (!mentionsValue(cell, claim) || UNBOUND_VERSION.matcher(cell).find()) continue;
                for (String version : namedVersions(columns.get(column))) {
                    if (!conditionPresent(cell, claim) || !explicitVersions(claim).contains(version)) fail(field);
                }
                // 纵向表的版本单元格和内容单元格构成同一断言；日期、签署状态的未知不取消它。
                if (columns.stream().noneMatch(header -> !namedVersions(header).isEmpty())) {
                    for (String versionCell : cells) {
                        if (UNBOUND_VERSION.matcher(versionCell).find()) continue;
                        for (String version : namedVersions(versionCell)) {
                            if (!conditionPresent(line, claim) || !explicitVersions(claim).contains(version)) fail(field);
                        }
                    }
                }
            }
        }
    }

    /** 表格检查后的普通句子继续保留原有比较、核对与未知边界。 */
    private void validateSentence(String line, String field) {
        if (UNKNOWN.matcher(line).find() || line.matches(".*(?:不得|不能|不要|禁止|核对|核实).*(?:绑定|归属|对应|拼接).*")) return;
        // 匿名一版／另一版只陈述内容；同句其他属性的A/B标签不为它建立对应。
        // 仅移除这段匿名比较，前后独立出现的具名归属断言仍须校验。
        String assertions = ALTERNATIVES.matcher(line).replaceAll("");
        // 仅移除独立的“某版本作为对照版本”角色子句，保留同句的具体条款断言。
        // 夹带条件、取值或其他谓语的子句不匹配，不能靠角色词绕过未知归属校验。
        assertions = COMPARISON_ROLE.matcher(assertions).replaceAll("$1");
        for (Claim claim : claims) {
            if (!assertions.contains(claim.subject()) || !mentionsValue(assertions, claim)) continue;
            if (VERSION.matcher(assertions).find() && assertions.matches(".*(?:写为|写|承担|负担|采用|规定|新增|要求|[：:]).*")) {
                var version = VERSION.matcher(assertions);
                while (version.find()) if (!conditionPresent(assertions, claim)
                        || !explicitVersions(claim).contains(canonicalVersion(version.group()))) fail(field);
            }
        }
    }

    /** 短A/B表头仅在资料已命名唯一相应版本时建立别名，不能从列顺序推断。 */
    private List<String> namedVersions(String text) {
        String label = text.strip();
        if (label.matches(".*(?:[？?]|对应关系|哪份|哪版|是否).*")) return List.of();
        var explicit = VERSION.matcher(label).results().toList();
        // 版本标签格是单个标签（可带括号状态或“内容”等列名），不是含多个版本的原文段落。
        if (!explicit.isEmpty()) {
            if (explicit.size() != 1 || explicit.getFirst().start() != 0) return List.of();
            String suffix = label.substring(explicit.getFirst().end()).strip();
            if (!suffix.matches("(?:(?:的)?(?:表述|记载|内容|条款))?(?:\\s*\\([^()]*\\))?")) return List.of();
            return List.of(canonicalVersion(explicit.getFirst().group()));
        }
        return label.matches("[A-Z甲乙丙丁一二三四0-9]{1,8}") ? uniqueAlias(label) : List.of();
    }

    /** 前置或后置版本名仅在当前材料能唯一解释该标识时等值化。 */
    private String canonicalVersion(String text) {
        String label = text.replaceAll("\\s", "");
        if (versions.contains(label)) return label;
        String code = label.replaceAll("草稿|版本|方案|文本|文稿|报告|版", "");
        var matched = uniqueAlias(code);
        return matched.size() == 1 ? matched.getFirst() : label;
    }

    /** 多份材料的同名代号无法唯一解释时，不让短表头替代完整版本名。 */
    private List<String> uniqueAlias(String code) {
        var matched = versions.stream().filter(version -> version.replaceAll("草稿|版本|方案|文本|文稿|报告|版", "").equals(code))
                .sorted().toList();
        return matched.size() == 1 ? matched : List.of();
    }

    /** 只有同一完整子句明确命名版本、同一属性和该取值，才证明归属；相邻段落不自动合并。 */
    private boolean explicitlyBound(Claim claim) {
        return !explicitVersions(claim).isEmpty();
    }

    /** 同一属性中，A的证据不为B背书；部分建立对应时仍分别核对各个版本。 */
    private Set<String> explicitVersions(Claim claim) {
        return bindingEvidence(claim).stream().map(Binding::version).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 每个对应关系保留真正建立它的来源，内容的初始出处不自动变成归属证据。 */
    private Set<Binding> bindingEvidence(Claim claim) {
        var values = new LinkedHashSet<Binding>();
        evidence.stream().flatMap(material -> java.util.Arrays.stream(material.text().split("[。；;\\n]"))
                        .map(line -> new Evidence(line, material.source())))
                .filter(material -> !UNKNOWN.matcher(material.text()).find())
                .filter(material -> !material.text().matches(".*(?:例如|假设|如果|若|是否|不得|不能|不应|不可认定|不要).*"))
                .filter(material -> VERSION.matcher(material.text()).find() && material.text().contains(claim.subject())
                        && !ALTERNATIVES.matcher(material.text()).find() && mentionsValue(material.text(), claim)
                        && conditionPresent(material.text(), claim)
                        && material.text().matches(".*(?:写为|写|承担|采用|规定|要求|为|是|[：:]).*"))
                .forEach(material -> {
                    var matches = VERSION.matcher(material.text());
                    // 同句两个版本而未分别给值也不证明唯一对应。
                    var named = matches.results().map(match -> match.group().replaceAll("\\s", "")).distinct().toList();
                    if (named.size() == 1) values.add(new Binding(canonicalVersion(named.getFirst()), material.source()));
                });
        return Set.copyOf(values);
    }

    private void fail(String field) {
        throw new ProviderResponseValidationException(ProviderResponseValidationException.Reason.SOURCE_SCOPE_CONFLICT, field);
    }

    private boolean mentionsValue(String line, Claim claim) {
        String value = claim.value().replaceFirst("^(?:写为|写|新增|规定|要求|是|为|采用)", "");
        if (!claim.condition().isBlank() && value.startsWith(claim.condition())) value = value.substring(claim.condition().length());
        return line.replaceAll("\\s", "").contains(value.replaceAll("\\s", ""));
    }

    /** 已绑定版本的条件仍是证据的一部分，不能因版本明确就把适用范围扩大。 */
    private boolean conditionPresent(String line, Claim claim) {
        return claim.condition().isBlank() || line.replaceAll("\\s", "").contains(claim.condition().replaceAll("\\s", ""));
    }

    private static boolean trusted(PlanningFactOrigin origin) {
        return origin == PlanningFactOrigin.USER_MATERIAL || origin == PlanningFactOrigin.PROJECT_DOCUMENT
                || origin == PlanningFactOrigin.PROJECT_SOURCE;
    }

    /** 路径只作为已过滤材料的出处，不用文件名或出现次序推断条款归属。 */
    private record Evidence(String text, String source) { }

    /** 只记录同属性、取值与条件完整核对过的具名对应及其证据出处。 */
    private record Binding(String version, String source) { }
}
