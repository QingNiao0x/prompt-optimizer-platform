package com.promptoptimizer.provider.domain;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 同次生成中的要求覆盖、缺口与提问影响记录；核对来源关联和最终正文锚点，不另调裁判。
 * 下标与引文不能证明语义等价或穷尽原要求，模型的语义自查仍须以实际作品验证。
 * 记录仅用于本次内部校验，不进入优化提示词、用户历史或日志。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalTaskReview(InputReadiness inputReadiness, List<Coverage> requirementCoverage,
        List<Coverage> additionCoverage, List<PlanImpact> planImpacts) {
    /** 与正文组装的换行识别一致，包含CRLF及Unicode行分隔符，仅用于内部引用协议。 */
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    public NaturalTaskReview {
        requirementCoverage = requirementCoverage == null ? List.of() : List.copyOf(requirementCoverage);
        additionCoverage = additionCoverage == null ? List.of() : List.copyOf(additionCoverage);
        planImpacts = planImpacts == null ? List.of() : List.copyOf(planImpacts);
    }

    /** 模型对需求充分程度的候选判断，只指导补全，不建立事实、授权或额外追问。 */
    public enum InputReadiness {
        /** 现有表达足以交付，优先保留有效原句。 */ SUFFICIENT,
        /** 需要补充适用内容或处理重要未知，不要求用户补齐所有执行细节。 */ NEEDS_COMPLETION
    }

    /** 引用理解记录的零起始下标及单行正文片段；多项要求可由同一句承担，跨行要求使用多个片段。 */
    public record Coverage(Integer index, List<String> quotes) {
        public Coverage { quotes = quotes == null ? List.of() : List.copyOf(quotes); }
    }

    /** 每道实际问题的必要性与答案差异；不能仅凭模型自报理由证明问题确实必要。 */
    public record PlanImpact(String questionId, String whyAsk, String answerDifference) { }

    /**
     * 在来源原句已校验后核对记录完整性；每份真实题答必须有来源关联，但关联本身不证明全部语义已保留。
     * 不要求六种维度全部出现，也不按职业、词表或模型自报充分程度过滤要求。
     */
    void validateUnderstanding(NaturalTaskUnderstanding task, NaturalPromptInput input) {
        if (inputReadiness == null || task.requirements().isEmpty() || requirementCoverage.size() > 256
                || additionCoverage.size() > 256 || planImpacts.size() > 64) throw invalid("覆盖检查记录不完整或超出容量");
        Set<String> referenced = new HashSet<>();
        for (var requirement : task.requirements()) {
            if (requirement.dimensions().isEmpty() || requirement.dimensions().size() > NaturalTaskUnderstanding.Dimension.values().length
                    || new HashSet<>(requirement.dimensions()).size() != requirement.dimensions().size()) {
                throw invalid("任务要求缺少有效核对维度");
            }
            requirement.evidence().forEach(evidence -> referenced.add(evidence.sourceId()));
        }
        Set<String> userSources = input.taskSources().stream()
                .filter(source -> source.kind() == NaturalTaskSource.Kind.ORIGINAL_REQUEST
                        || source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER)
                .map(NaturalTaskSource::id).collect(Collectors.toSet());
        if (!referenced.containsAll(userSources)) throw invalid("覆盖记录遗漏原始需求或实际题答来源");
        for (var addition : task.additions()) check(addition.gap());
        requirementCoverage.forEach(NaturalTaskReview::checkCoverage);
        additionCoverage.forEach(NaturalTaskReview::checkCoverage);
        for (var impact : planImpacts) {
            if (impact == null) throw invalid("Plan影响记录为空");
            check(impact.questionId()); check(impact.whyAsk()); check(impact.answerDifference());
        }
    }

    /** 在模型正文及平台组装后分别核对锚点，防止有覆盖声明却无对应原句；不拼接模型未交付的要求。 */
    public void validateEnhancement(NaturalTaskUnderstanding task, String body) {
        if (!planImpacts.isEmpty()) throw invalid("正文检查混入了新的Plan提问");
        validateCoverage(requirementCoverage, task.requirements().size(), body);
        validateCoverage(additionCoverage, task.additions().size(), body);
    }

    /** Plan尚无最终正文；只核对真实题号及影响依据，不凭分类标记删除问题或替用户作答。 */
    public void validatePlan(Set<String> questionIds) {
        if (!requirementCoverage.isEmpty() || !additionCoverage.isEmpty()) throw invalid("Plan不能宣称最终正文已覆盖");
        Set<String> recorded = new HashSet<>();
        for (var impact : planImpacts) {
            if (impact == null) throw invalid("Plan影响记录为空");
            check(impact.questionId()); check(impact.whyAsk()); check(impact.answerDifference());
            if (!recorded.add(impact.questionId())) throw invalid("Plan影响记录重复");
        }
        if (!recorded.equals(questionIds)) throw invalid("Plan问题缺少必要性或答案差异记录");
    }

    /** 覆盖全部已登记下标，禁止重复、越界或借另一候选的正文片段充当本次证明。 */
    private static void validateCoverage(List<Coverage> coverage, int expected, String body) {
        if (coverage.size() != expected || body == null || body.isBlank()) throw invalid("正文覆盖记录不完整");
        Set<Integer> seen = new HashSet<>();
        for (var item : coverage) {
            checkCoverage(item);
            if (item.index() >= expected || !seen.add(item.index())
                    || item.quotes().stream().anyMatch(quote -> !body.contains(quote))) {
                throw invalid("正文覆盖记录越界、重复或缺少对应原句");
            }
        }
    }

    /** 内部记录同样检查容量和敏感值，不能借诊断元数据绕过正文保护。 */
    private static void checkCoverage(Coverage coverage) {
        if (coverage == null || coverage.index() == null || coverage.index() < 0
                || coverage.quotes().isEmpty() || coverage.quotes().size() > 16) throw invalid("正文覆盖条目无效");
        for (String quote : coverage.quotes()) {
            check(quote);
            // 引用不跨行，避免换行规范化或完整权限原句合法归并使无语义变化的结果被误拒。
            // 仅约束内部锚点，用户正文、代码围栏和来源原句仍保留原格式。
            if (LINE_BREAK.matcher(quote).find()) throw invalid("覆盖引用应使用单行片段");
        }
    }

    /** 引用与影响文字必须可检查，不按领域词表推测其语义是否正确。 */
    private static void check(String value) {
        if (value == null || value.isBlank() || value.length() > 20_000
                || new SensitiveValueScanner().containsCredential(value)) throw invalid("覆盖检查字段无效");
    }

    private static ProviderException invalid(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false);
    }
}
