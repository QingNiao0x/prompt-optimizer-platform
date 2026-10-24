package com.promptoptimizer.provider.domain;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 简短任务理解记录，用于核对来源及补充内容的职责，不是用户表单。
 * 正文、补全准备及局部修订各阶段独立校验本记录，不以较早记录宣称最终正文已经覆盖。
 * 完整原始提示词、资料和题答仍是生成依据；本记录不替代原文，也不持久化文件引用原句。
 * goal可表达理解、判断及条件动作；deliverable允许为空，当前任务完整不要求另有文档或代码作品。
 * 来源和覆盖只证明记录关联，不证明模型对动作、成果或授权的语义判断必然正确。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalTaskUnderstanding(String goal, String deliverable, List<Requirement> requirements,
        List<Addition> additions, List<OpenDecision> openDecisions, NaturalTaskReview review) {
    public NaturalTaskUnderstanding {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        additions = additions == null ? List.of() : List.copyOf(additions);
        openDecisions = openDecisions == null ? List.of() : List.copyOf(openDecisions);
    }

    /** 第一至三版不携带覆盖记录；保留旧协议及离线夹具，不伪造已完成检查。 */
    public NaturalTaskUnderstanding(String goal, String deliverable, List<Requirement> requirements,
            List<Addition> additions, List<OpenDecision> openDecisions) {
        this(goal, deliverable, requirements, additions, openDecisions, null);
    }

    /** 原有要求的候选理解，必须能在服务端来源目录中找到原句；来源不等于事实真实性证明。 */
    public record Requirement(String text, List<Evidence> evidence, List<Dimension> dimensions) {
        public Requirement {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
        /** 旧版要求未分类，仍完整保留其出处，不从词表反推类别。 */
        public Requirement(String text, List<Evidence> evidence) { this(text, evidence, List.of()); }
    }

    /** 原有要求的核对维度；只组织实际存在的信息，不为分类新增用户要求。 */
    public enum Dimension {
        /** 任务动作及目标作品。 */ GOAL,
        /** 处理对象、受众及适用范围。 */ OBJECT,
        /** 交付形式与输出格式。 */ FORMAT,
        /** 用户明确的篇幅、数量或时长。 */ LENGTH,
        /** 独立前提、否定、例外及业务边界。 */ CONDITION,
        /** 用户实际确认的信息，仍以服务端来源为准。 */ USER_CONFIRMATION
    }

    /** 模型只能提供来源编号及原句；实际类别在校验后由服务端覆盖，不能自报用户确认。 */
    public record Evidence(String sourceId, String quote, NaturalTaskSource.Kind origin) {
        public Evidence(String sourceId, String quote) { this(sourceId, quote, null); }
    }

    /** 对原定交付有直接帮助的拟议补充；benefit仅记录用途，不要求输出模型推理过程。 */
    public record Addition(String text, String benefit, String gap) {
        /** 旧版补充没有缺口字段，不追加虚构依据。 */
        public Addition(String text, String benefit) { this(text, benefit, null); }
    }

    /** 重要未知及其交付影响；Plan提问时关联真实题号，其余情况questionId可为空。 */
    public record OpenDecision(String issue, String impact, String questionId, DecisionKind kind) {
        public OpenDecision { kind = kind == null ? DecisionKind.USER_INFORMATION : kind; }
        /** 旧记录或未分类的未知保守视作用户保留信息，仅影响推荐显示，不建立事实。 */
        public OpenDecision(String issue, String impact, String questionId) { this(issue, impact, questionId, DecisionKind.USER_INFORMATION); }
    }

    /** 模型候选的未知类型只用于收紧推荐能力，不能用于建立用户确认、事实或授权。 */
    public enum DecisionKind {
        /** 用户独有的现状、数值、资源边界或保留参数，不能推荐一个猜测值。 */
        USER_INFORMATION,
        /** 可讨论的执行方式或偏好，允许给出有依据且可拒绝的建议。 */
        EXECUTION_CHOICE
    }

    /**
     * 验证引用原句并补上服务端来源类别，不凭模型自报状态建立用户确认。
     * 这里只校验协议、出处和敏感值，不将来源存在误当作全部要求已覆盖或正文语义正确。
     */
    public NaturalTaskUnderstanding resolve(NaturalPromptInput input) {
        check(goal, true);
        check(deliverable, false);
        if (requirements.size() > 256 || additions.size() > 256 || openDecisions.size() > 64) {
            throw invalid("任务理解记录超出协议容量");
        }
        Map<String, NaturalTaskSource> sources = input.taskSources().stream()
                .collect(Collectors.toMap(NaturalTaskSource::id, Function.identity()));
        List<Requirement> resolved = requirements.stream().map(requirement -> {
            if (requirement == null) throw invalid("任务要求为空");
            check(requirement.text(), true);
            if (requirement.evidence().isEmpty() || requirement.evidence().size() > 64) throw invalid("任务要求缺少有效出处");
            return new Requirement(requirement.text(), requirement.evidence().stream().map(evidence -> {
                if (evidence == null) throw invalid("任务出处为空");
                NaturalTaskSource source = sources.get(evidence.sourceId());
                check(evidence.quote(), true);
                if (source == null || !source.text().contains(evidence.quote())) throw invalid("任务理解引用不属于当前可用来源");
                return new Evidence(source.id(), evidence.quote(), source.kind());
            }).toList(), requirement.dimensions());
        }).toList();
        additions.forEach(addition -> {
            if (addition == null) throw invalid("任务补充为空");
            check(addition.text(), true); check(addition.benefit(), true);
        });
        openDecisions.forEach(decision -> {
            if (decision == null) throw invalid("重要未知为空");
            check(decision.issue(), true); check(decision.impact(), true); check(decision.questionId(), false);
        });
        var result = new NaturalTaskUnderstanding(goal, deliverable, resolved, additions, openDecisions, review);
        if (NaturalPromptInstructions.requiresTaskReview(input.strategyVersion())) {
            if (review == null) throw invalid("自然链路缺少覆盖检查记录");
            review.validateUnderstanding(result, input);
        }
        return result;
    }

    /** 统一检查内部生成字段，避免敏感值通过诊断记录绕过正文扫描；不做场景关键词判断。 */
    private static void check(String value, boolean required) {
        if ((required && (value == null || value.isBlank())) || (value != null
                && (value.length() > 20_000 || new SensitiveValueScanner().containsCredential(value)))) {
            throw invalid("任务理解字段无效");
        }
    }

    private static ProviderException invalid(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false);
    }
}
