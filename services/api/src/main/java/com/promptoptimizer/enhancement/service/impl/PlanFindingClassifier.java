package com.promptoptimizer.enhancement.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 区分真正的用户决定、已核实规则及执行 Agent 的代码核查步骤。
 * 只接受可逐句核对的证据或明确核查指令；无法证明的陈述继续保留，不能静默消除冲突。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanFindingClassifier {
    enum Kind { UNRESOLVED, KNOWN_RULE, IMPLEMENTATION_CHECK }

    private static final Pattern DECISION = Pattern.compile("[？?]|是否|尚未|未确定|未明确|未提供|未决定|暂不确定|待定|缺少|冲突|不一致|用户确认|请确认|需确认|待确认");
    private static final Pattern LAYER = Pattern.compile("(服务端|后端|前端|客户端)(?:负责|实现)(.{2,40})");

    /** 不向模型授予声明已解决的权力，分类只依赖本次安全资料和服务端绑定答案。 */
    Kind classify(String finding, List<String> evidence) {
        return classify(finding, evidence, evidence);
    }

    /** 整段资料仅核对显式排除关系；一般业务断言仍只匹配逐句相关的事实。 */
    Kind classify(String finding, List<String> evidence, List<String> exclusionEvidence) {
        if (DECISION.matcher(finding).find()) return Kind.UNRESOLVED;
        if (verifiedExclusion(finding, exclusionEvidence)) return Kind.KNOWN_RULE;
        // 这类语句要求执行者先读工程，而不是要求用户补齐业务选择；保留全文到任务段落。
        if (clauses(finding).stream().allMatch(clause -> clause.matches(".{2,80}(?:以现有(?:代码|工程)核查结果为准|(?:请)?先核查现有(?:代码|工程|实现))")
                || clause.matches("(?:不新增或猜测接口参数|不凭材料假定存在.{1,30}|不得猜测.{1,30})"))
                && finding.matches("(?s).*(?:核查结果为准|先核查现有).*")) {
            return Kind.IMPLEMENTATION_CHECK;
        }
        List<String> facts = evidence.stream().flatMap(value -> clauses(value).stream())
                .filter(value -> !value.matches(".*(?:建议|候选|示例|例如|假设|待定|尚未|可能|不是|并非).*"))
                .map(this::canonical).toList();
        List<String> statements = clauses(finding);
        if (statements.isEmpty()) return Kind.UNRESOLVED;
        String first = canonical(statements.getFirst());
        var relation = LAYER.matcher(first);
        if (relation.matches() && facts.contains(first)) {
            String layer = relation.group(1);
            String subject = relation.group(2);
            boolean opposite = facts.stream().map(LAYER::matcher).anyMatch(match -> match.matches()
                    && subject.equals(match.group(2)) && !layer.equals(match.group(1)));
            if (opposite) return Kind.UNRESOLVED;
            String other = layer.equals("服务端") ? "前端" : "服务端";
            if (statements.stream().skip(1).allMatch(part -> facts.contains(canonical(part))
                    || canonical(part).equals(other + "不承担该职责")
                    || layer.equals("服务端") && (canonical(part).equals("前端按服务端返回结果处理")
                        || canonical(part).equals("不自行实现或放宽过滤") && subject.endsWith("过滤")))) return Kind.KNOWN_RULE;
        }
        return statements.stream().map(this::canonical).allMatch(facts::contains) ? Kind.KNOWN_RULE : Kind.UNRESOLVED;
    }

    /** 仅核对材料明确排除的模块规则；完整对象、限定词和额外业务条件不能因“无关”二字被丢弃。 */
    private boolean verifiedExclusion(String finding, List<String> evidence) {
        var statement = Pattern.compile("^(.{2,80}?)规则与本次任务无关[，,]不得套用到([^，,。；;]{2,40})[。]?$")
                .matcher(finding);
        if (!statement.matches()) return false;
        String target = canonical(statement.group(2));
        for (String source : evidence) {
            if (source.matches("(?s).*(?:建议|候选|假设|待定|尚未|可能).*")) continue;
            var scope = Pattern.compile("规则仅适用于([^，,。；;]{2,40})[，,](?:不是|不适用于)([^，,。；;]{2,40}?)规则")
                    .matcher(source);
            if (!scope.find() || !canonical(scope.group(2)).equals(target)
                    || !canonical(statement.group(1)).startsWith(canonical(scope.group(1)) + "的")) continue;
            String normalized = canonical(source);
            String detail = canonical(statement.group(1)).substring(canonical(scope.group(1)).length() + 1);
            boolean allSourced = Arrays.stream(detail.split("与|和|及|、")).allMatch(part ->
                    part.length() >= 2 && normalized.contains(part));
            // 新材料声称适用同一目标时仍是冲突，不能通过负面范围提醒消除它。
            boolean conflict = evidence.stream().anyMatch(value -> canonical(value)
                    .matches(".*(?<!不)(?:适用于|应用于)" + Pattern.quote(target) + ".*"));
            if (allSourced && !conflict) return true;
        }
        return false;
    }

    /** 保留否定和所有谓词，仅按句读切分可分别核对的事实。 */
    private List<String> clauses(String text) {
        return Arrays.stream(text.split("[。；;，,\\r\\n]+"))
                .map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    /** 只规范化同一职责关系的主动/被动语序，不丢弃否定、对象、数值或额外条件。 */
    private String canonical(String text) {
        String value = text.toLowerCase(Locale.ROOT).replaceAll("[\\s`*]", "")
                .replace("后端", "服务端").replace("客户端", "前端");
        var passive = Pattern.compile("^(.{2,40}?)由(服务端|前端)(负责|实现)$").matcher(value);
        return passive.matches() ? passive.group(2) + passive.group(3) + passive.group(1) : value;
    }
}
