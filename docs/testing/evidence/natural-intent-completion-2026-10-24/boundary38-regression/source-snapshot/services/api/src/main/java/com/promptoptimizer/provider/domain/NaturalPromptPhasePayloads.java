package com.promptoptimizer.provider.domain;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 自然增强21起的分阶段模型协议，仅负责把邻接字段映射为既有来源及覆盖记录。
 * 不补造语义、事实或引用，不持久化内部记录；严格字段解析由Provider的候选专用读取器负责。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptPhasePayloads {
    private NaturalPromptPhasePayloads() { }

    /** 来源类别仍由服务器目录决定，模型只提交来源编号与连续原句。 */
    public record SourceEvidence(String sourceId, String quote) {
        private NaturalTaskUnderstanding.Evidence toEvidence() {
            return new NaturalTaskUnderstanding.Evidence(sourceId, quote);
        }
    }

    /** Plan中的原有要求；没有尚未生成的正文引用。 */
    public record Requirement(String text, List<NaturalTaskUnderstanding.Dimension> dimensions,
            List<SourceEvidence> evidence) {
        public Requirement {
            dimensions = copy(dimensions);
            evidence = copy(evidence);
        }

        private NaturalTaskUnderstanding.Requirement toRequirement() {
            return requirement(text, dimensions, evidence);
        }
    }

    /** 增强要求及其正文锚点位于同一条目，数组位置由服务端转为内部Coverage下标。 */
    public record CoveredRequirement(String text, List<NaturalTaskUnderstanding.Dimension> dimensions,
            List<SourceEvidence> evidence, List<String> bodyQuotes) {
        public CoveredRequirement {
            dimensions = copy(dimensions);
            evidence = copy(evidence);
            bodyQuotes = copy(bodyQuotes);
        }

        private NaturalTaskUnderstanding.Requirement toRequirement() {
            return requirement(text, dimensions, evidence);
        }
    }

    /** 拟议补充的语义依据与交付位置；缺口、收益、引文均必须来自模型实际响应。 */
    public record CoveredAddition(String text, String gap, String benefit, List<String> bodyQuotes) {
        public CoveredAddition { bodyQuotes = copy(bodyQuotes); }
    }

    /** 未提问的未知保持原有语义；空题号由服务端表达，不制造问题或默认答案。 */
    public record PendingDecision(String issue, String impact, NaturalTaskUnderstanding.DecisionKind kind) {
        /** 模型仍须说明未知性质，服务器只补无关联题号的表示。 */
        private NaturalTaskUnderstanding.OpenDecision toDecision() {
            if (kind == null) throw invalid("未决信息缺少类型");
            return new NaturalTaskUnderstanding.OpenDecision(issue, impact, "", kind);
        }
    }

    /** 增强阶段的任务记录，不接受Plan影响数组或模型维护的重复下标表。 */
    public record EnhancementTask(String goal, String deliverable, NaturalTaskReview.InputReadiness inputReadiness,
            List<CoveredRequirement> requirements, List<CoveredAddition> additions, List<PendingDecision> openDecisions) {
        public EnhancementTask {
            requirements = copy(requirements);
            additions = copy(additions);
            openDecisions = copy(openDecisions);
        }

        /** 仅由所在位置产生下标，不能用原文补写模型缺失的正文锚点。 */
        private NaturalTaskUnderstanding toUnderstanding(NaturalPromptInput input) {
            var required = list(requirements, 256);
            var added = list(additions, 256);
            var pending = list(openDecisions, 64);
            List<NaturalTaskReview.Coverage> requirementCoverage = new ArrayList<>();
            List<NaturalTaskReview.Coverage> additionCoverage = new ArrayList<>();
            for (int i = 0; i < required.size(); i++) {
                requirementCoverage.add(new NaturalTaskReview.Coverage(i, list(required.get(i).bodyQuotes(), 16)));
            }
            for (int i = 0; i < added.size(); i++) {
                additionCoverage.add(new NaturalTaskReview.Coverage(i, list(added.get(i).bodyQuotes(), 16)));
            }
            return new NaturalTaskUnderstanding(goal, deliverable,
                    required.stream().map(CoveredRequirement::toRequirement).toList(),
                    added.stream().map(value -> new NaturalTaskUnderstanding.Addition(value.text(), value.benefit(), value.gap())).toList(),
                    pending.stream().map(PendingDecision::toDecision).toList(),
                    new NaturalTaskReview(inputReadiness, requirementCoverage, additionCoverage, List.of())).resolve(input);
        }
    }

    /** 自然正文响应；框架必填与否只由候选版本确定，不能由模型关闭保护。 */
    public record Enhancement(String optimizedPrompt, String framework, EnhancementTask task) {
        /** 使用已有来源及正文覆盖验证，再交给应用层执行组装后验证与一次历史保存。 */
        public NaturalPromptDraft toDraft(NaturalPromptInput input, ProviderMetadata metadata) {
            requireVersion(input);
            if (task == null) throw invalid("自然增强缺少阶段任务记录");
            requiredContent(optimizedPrompt);
            String body = optimizedPrompt.strip();
            NaturalTaskUnderstanding understanding = task.toUnderstanding(input);
            understanding.review().validateEnhancement(understanding, body);
            return new NaturalPromptDraft(body, resolveFramework(framework, input.strategyVersion()), metadata, understanding);
        }
    }

    /** Plan的任务记录不需要填补正文；未提问的独立未知单独保留。 */
    public record PlanningTask(String goal, String deliverable, NaturalTaskReview.InputReadiness inputReadiness,
            List<Requirement> requirements, List<PendingDecision> unaskedDecisions) {
        public PlanningTask {
            requirements = copy(requirements);
            unaskedDecisions = copy(unaskedDecisions);
        }
    }

    /** 一道题涉及的独立未知及答案差异；保留多条依据，不将其中之一代表整道题。 */
    public record DecisionBasis(String issue, NaturalTaskUnderstanding.DecisionKind kind,
            String whyAsk, String answerDifference) { }

    /** 候选专用选项DTO，不接受现有实体可能忽略的内部字段或模型自报责任状态。 */
    public record Option(String id, String label, String description, String answer,
            Boolean recommended, String recommendationReason) {
        /** 转换UI选项，不把缺失的推荐标记当成用户已作选择。 */
        private PlanOption toOption() {
            if (recommended == null) throw invalid("Plan选项缺少推荐标记");
            return new PlanOption(id, label, description, answer, recommended, recommendationReason);
        }
    }

    /** 题号只在此出现一次，decisionBasis所属对象决定其内部题号关联。 */
    public record Question(String id, String question, String hint, PlanQuestionType type,
            List<Option> options, List<String> examples, Boolean allowCustomAnswer, List<DecisionBasis> decisionBasis) {
        public Question {
            options = copy(options);
            examples = copy(examples);
            decisionBasis = copy(decisionBasis);
        }

        /** 仅搬运题目与选项，字段长度、自由回答和推荐责任继续经过应用层校验。 */
        private PlanQuestion toQuestion() {
            if (allowCustomAnswer == null || type == null) throw invalid("Plan题目缺少类型或自由回答标记");
            return new PlanQuestion(id, question, hint, type,
                    list(options, 12).stream().map(Option::toOption).toList(), list(examples, 8), allowCustomAnswer);
        }
    }

    /** Plan响应中不存在最终正文或正文覆盖声明，转换后继续复用既有会话与提问校验。 */
    public record Planning(String summary, PlanningTask task, List<Question> questions) {
        public Planning { questions = copy(questions); }

        /** 服务端从嵌套归属构造链接，语义依据仍全部来自当前模型响应。 */
        public PlanningProviderResponse toResponse(NaturalPromptInput input, ProviderMetadata metadata) {
            requireVersion(input);
            if (task == null) throw invalid("Plan缺少阶段任务记录");
            requiredContent(summary);
            var actualQuestions = list(questions, 64);
            List<NaturalTaskUnderstanding.OpenDecision> decisions = new ArrayList<>(list(task.unaskedDecisions(), 64)
                    .stream().map(PendingDecision::toDecision).toList());
            List<NaturalTaskReview.PlanImpact> impacts = new ArrayList<>();
            List<PlanQuestion> normalizedQuestions = new ArrayList<>();
            var ids = new HashSet<String>();
            for (Question question : actualQuestions) {
                if (question.id() == null || !question.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(question.id())) {
                    throw invalid("Plan题号无效或重复");
                }
                var bases = list(question.decisionBasis(), 64);
                if (bases.isEmpty()) throw invalid("Plan问题缺少决策依据");
                List<String> reasons = new ArrayList<>(), differences = new ArrayList<>();
                for (DecisionBasis basis : bases) {
                    requiredText(basis.issue()); requiredText(basis.whyAsk()); requiredText(basis.answerDifference());
                    if (basis.kind() == null) throw invalid("Plan决策依据缺少类型");
                    decisions.add(new NaturalTaskUnderstanding.OpenDecision(basis.issue(), basis.answerDifference(),
                            question.id(), basis.kind()));
                    reasons.add(basis.whyAsk());
                    differences.add(basis.answerDifference());
                }
                // 一个内部PlanImpact对应一题；逐条保留全部依据，不截断、不以第一项替代其它条件。
                impacts.add(new NaturalTaskReview.PlanImpact(question.id(), String.join("\n", reasons), String.join("\n", differences)));
                normalizedQuestions.add(question.toQuestion());
            }
            var understanding = new NaturalTaskUnderstanding(task.goal(), task.deliverable(),
                    list(task.requirements(), 256).stream().map(Requirement::toRequirement).toList(), List.of(), decisions,
                    new NaturalTaskReview(task.inputReadiness(), List.of(), List.of(), impacts)).resolve(input);
            understanding.review().validatePlan(ids);
            return new PlanningProviderResponse(summary.strip(), normalizedQuestions, metadata.provider(), metadata.model(), metadata.mock(), understanding);
        }
    }

    /** 映射来源原句，不接受模型自报origin；resolve继续核对当前可用来源及全部实际题答。 */
    private static NaturalTaskUnderstanding.Requirement requirement(String text,
            List<NaturalTaskUnderstanding.Dimension> dimensions, List<SourceEvidence> evidence) {
        return new NaturalTaskUnderstanding.Requirement(text,
                list(evidence, 64).stream().map(SourceEvidence::toEvidence).toList(),
                list(dimensions, NaturalTaskUnderstanding.Dimension.values().length));
    }

    /** 可选框架缺失或无法识别时只写既有APE默认标记，不将其解释为模型已匹配某种任务。 */
    private static NaturalPromptDraft.Framework resolveFramework(String value, String version) {
        if (value != null && (value.length() > 128 || new SensitiveValueScanner().containsCredential(value))) {
            throw invalid("自然框架标记无效");
        }
        if (value != null && !value.isBlank()) {
            try {
                return NaturalPromptDraft.Framework.valueOf(value.strip());
            } catch (IllegalArgumentException ignored) {
                // 21沿用原必填枚举约束；22/23仅诊断标签不能阻断已经通过正文与来源校验的交付。
            }
        }
        if (NaturalPromptStageInstructions.requiresFramework(version)) throw invalid("自然框架标记缺失或无效");
        return NaturalPromptDraft.Framework.APE;
    }

    /** 新DTO不得用来宽松读取旧响应，版本选择只来自服务端入口。 */
    private static void requireVersion(NaturalPromptInput input) {
        if (!NaturalPromptStageInstructions.supports(input.strategyVersion())) throw invalid("阶段协议版本不受支持");
    }

    /** 保留字段缺失供转换时明确失败，不能把缺失数组自动当成合法的零问题或零补充。 */
    private static <T> List<T> copy(List<T> values) {
        if (values == null) return null;
        if (values.stream().anyMatch(java.util.Objects::isNull)) throw invalid("阶段协议数组包含空条目");
        return List.copyOf(values);
    }

    /** 沿用既有协议容量并拒绝缺失列表，不截取前几项后宣称保留完整。 */
    private static <T> List<T> list(List<T> values, int maximum) {
        if (values == null || values.size() > maximum) throw invalid("阶段协议数组缺失或超出容量");
        return values;
    }

    /** 内部语义字段沿用原有容量，不能用空依据伪造完整Plan影响记录。 */
    private static void requiredText(String value) {
        if (value == null || value.isBlank() || value.length() > 20_000
                || new SensitiveValueScanner().containsCredential(value)) throw invalid("阶段协议文字无效");
    }

    /** 正文与Plan摘要沿用既有非空及敏感校验，不把内部记录容量变成新的作品篇幅上限。 */
    private static void requiredContent(String value) {
        if (value == null || value.isBlank() || new SensitiveValueScanner().containsCredential(value)) {
            throw invalid("阶段协议正文无效");
        }
    }

    private static ProviderException invalid(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false);
    }
}
