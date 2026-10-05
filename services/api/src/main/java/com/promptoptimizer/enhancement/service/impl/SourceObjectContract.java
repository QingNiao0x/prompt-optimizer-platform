package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    private static final Pattern VERSION = Pattern.compile("(?:草稿|版本|方案|文本|文稿|报告)[\\s]*[A-Z甲乙丙丁一二三四0-9]{1,8}");
    private static final Pattern UNKNOWN = Pattern.compile("未(?:明确|确定|绑定|建立|核实|对应|标明)|尚不清楚|待核实|待确认|对应未知|归属未知|无法对应|对应关系.*未知");
    private static final Pattern CONDITION = Pattern.compile("^((?:在|当|如果|若)[^，,。；;\\n]{1,40}?(?:时|情况下|条件下)[，,:：]?)(.+)$");
    private final List<Claim> claims;
    private final List<String> evidence;
    private final Set<String> versions;

    private SourceObjectContract(List<Claim> claims, List<String> evidence, Set<String> versions) {
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
        var contract = new SourceObjectContract(List.copyOf(claims), materials.stream().map(Evidence::text).toList(), versions);
        var represented = new LinkedHashSet<Claim>(claims);
        for (Claim claim : claims) for (String version : contract.explicitVersions(claim)) {
            represented.add(new Claim(claim.object(), claim.property(), version, claim.condition(), claim.value(),
                    State.EXPLICIT_VERSION, claim.source()));
        }
        return new SourceObjectContract(List.copyOf(represented), materials.stream().map(Evidence::text).toList(), versions);
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

    /** 只拒绝把未绑定取值断言给具体版本；比较指令、未知标记及其他属性不被拦截。 */
    void validate(String content, String field) {
        if (claims.isEmpty() || content == null) return;
        List<String> columns = List.of();
        for (String line : content.split("[。；;\\n]")) {
            if (line.strip().startsWith("|") && VERSION.matcher(line).find()
                    && claims.stream().noneMatch(claim -> mentionsValue(line, claim))) {
                columns = java.util.Arrays.asList(line.split("\\|", -1));
                continue;
            }
            if (!columns.isEmpty() && line.strip().startsWith("|")) {
                var cells = line.split("\\|", -1);
                for (Claim claim : claims) {
                    if (!line.contains(claim.object())) continue;
                    for (int column = 0; column < Math.min(cells.length, columns.size()); column++) {
                        var version = VERSION.matcher(columns.get(column));
                        if (version.find() && mentionsValue(cells[column], claim)
                                && !UNKNOWN.matcher(cells[column]).find()
                                && (!conditionPresent(cells[column], claim)
                                || !explicitVersions(claim).contains(version.group().replaceAll("\\s", "")))) fail(field);
                    }
                }
            }
            if (UNKNOWN.matcher(line).find() || line.matches(".*(?:不得|不能|不要|禁止|核对|核实).*(?:绑定|归属|对应|拼接).*")) continue;
            for (Claim claim : claims) {
                if (!line.contains(claim.subject()) || !mentionsValue(line, claim)) continue;
                if (VERSION.matcher(line).find() && line.matches(".*(?:写为|写|承担|负担|采用|规定|新增|要求|[：:]).*")) {
                    var version = VERSION.matcher(line);
                    while (version.find()) if (!conditionPresent(line, claim)
                            || !explicitVersions(claim).contains(version.group().replaceAll("\\s", ""))) fail(field);
                }
            }
        }
    }

    /** 只有同一完整子句明确命名版本、同一属性和该取值，才证明归属；相邻段落不自动合并。 */
    private boolean explicitlyBound(Claim claim) {
        return !explicitVersions(claim).isEmpty();
    }

    /** 同一属性中，A的证据不为B背书；部分建立对应时仍分别核对各个版本。 */
    private Set<String> explicitVersions(Claim claim) {
        var values = new LinkedHashSet<String>();
        evidence.stream().flatMap(text -> java.util.Arrays.stream(text.split("[。；;\\n]")))
                .filter(line -> !UNKNOWN.matcher(line).find())
                .filter(line -> !line.matches(".*(?:例如|假设|如果|若|是否|不得|不能|不应|不可认定|不要).*"))
                .filter(line -> VERSION.matcher(line).find() && line.contains(claim.subject())
                        && !ALTERNATIVES.matcher(line).find() && mentionsValue(line, claim) && conditionPresent(line, claim)
                        && line.matches(".*(?:写为|写|承担|采用|规定|要求|为|是|[：:]).*"))
                .forEach(line -> {
                    var matches = VERSION.matcher(line);
                    // 同句两个版本而未分别给值也不证明唯一对应。
                    var named = matches.results().map(match -> match.group().replaceAll("\\s", "")).distinct().toList();
                    if (named.size() == 1) values.add(named.getFirst());
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
}
