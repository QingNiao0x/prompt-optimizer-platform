package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 49正文只回原要求ID和实际正文引文，并允许按完整原输入稀疏替换误解或补充漏项。
 * 规范化只恢复旧任务记录形状，不向正文拼接要求，也不凭来源/引文存在证明语义完整。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalPromptReferencedEnhancement(String optimizedPrompt, String framework, String goal, String deliverable,
        NaturalTaskReview.InputReadiness inputReadiness, List<Reference> coverage, List<Change> requirementChanges,
        List<NaturalPromptPhasePayloads.CoveredAddition> additions, List<NaturalPromptPhasePayloads.PendingDecision> openDecisions) {

    public NaturalPromptReferencedEnhancement {
        coverage = coverage == null ? null : List.copyOf(coverage);
        requirementChanges = requirementChanges == null ? null : List.copyOf(requirementChanges);
        additions = additions == null ? null : List.copyOf(additions);
        openDecisions = openDecisions == null ? null : List.copyOf(openDecisions);
    }

    /** 只引用本次服务端reqId；不能自行重发原要求、来源表或发行新ID。 */
    public record Reference(String reqId, List<String> bodyQuotes) {
        public Reference { bodyQuotes = bodyQuotes == null ? null : List.copyOf(bodyQuotes); }
    }

    /** 替换保留原ID，新增使用null；无纯删除，旧解释不强写回正文但原准备证据链仍独立保留。 */
    public record Change(String replacesId, NaturalPromptPhasePayloads.Requirement requirement, List<String> bodyQuotes) {
        public Change { bodyQuotes = bodyQuotes == null ? null : List.copyOf(bodyQuotes); }
    }

    /** 仅用于本次规范化审计的编号链接；原要求全文只保留在准备及canonical记录，不再复制整张表。 */
    public record Binding(String reqId, int requirementIndex, Integer changeIndex) { }

    /** 草稿与服务端实际编号/补正位置；不进入模型响应、复制正文或用户事实来源。 */
    public record Normalized(NaturalPromptDraft draft, List<Binding> bindings) {
        public Normalized { bindings = List.copyOf(bindings); }
    }

    /** 返回原应用能继续验证的草稿；编号和补正来源由同一normalize过程确定。 */
    public NaturalPromptDraft toDraft(NaturalPromptInput input, ProviderMetadata metadata, NaturalPromptRequirementRegistry.Prepared prepared) {
        return normalize(input, metadata, prepared).draft();
    }

    /**
     * 从真实准备结果及writer明确返回的引文构造canonical记录，继续执行原来源/全部题答保护。
     * 原稿、安全和最终组装覆盖仍由原应用校验执行；不能自动补证据或把准备分类升级为最终任务。
     */
    public Normalized normalize(NaturalPromptInput input, ProviderMetadata metadata, NaturalPromptRequirementRegistry.Prepared prepared) {
        return normalize(input, metadata, prepared.requirements());
    }

    /** 53只传已核验的typed entries；旧入口仍用同一算法，不为去掉scope补造动作或正文。 */
    public Normalized normalize(NaturalPromptInput input, ProviderMetadata metadata, List<NaturalPromptRequirementRegistry.Entry> registered) {
        var actualEntries = new NaturalPromptRequirementsOnlyPreparation.Registered(registered).requirements();
        if (optimizedPrompt == null || optimizedPrompt.isBlank() || framework == null || framework.isBlank()) throw invalid("自然正文或框架标识不完整");
        var references = NaturalPromptRequirementRegistry.bounded(coverage, 256);
        var changes = NaturalPromptRequirementRegistry.bounded(requirementChanges, 256);
        var added = NaturalPromptRequirementRegistry.bounded(additions, 256);
        var pending = NaturalPromptRequirementRegistry.bounded(openDecisions, 64);
        Map<String, NaturalPromptRequirementRegistry.Entry> entries = new HashMap<>();
        actualEntries.forEach(entry -> entries.put(entry.reqId(), entry));
        Map<String, Integer> replacements = new HashMap<>();
        for (int changeIndex = 0; changeIndex < changes.size(); changeIndex++) {
            Change change = changes.get(changeIndex);
            if (change.requirement() == null) throw invalid("原要求补正缺少明确内容");
            if (change.replacesId() != null && (!entries.containsKey(change.replacesId()) || replacements.putIfAbsent(change.replacesId(), changeIndex) != null)) {
                throw invalid("原要求补正引用未知或重复编号");
            }
        }
        Map<String, Reference> byId = new HashMap<>();
        for (Reference reference : references) {
            if (!entries.containsKey(reference.reqId()) || replacements.containsKey(reference.reqId()) || byId.putIfAbsent(reference.reqId(), reference) != null) {
                throw invalid("正文覆盖引用未知、重复或已补正编号");
            }
        }
        List<NaturalTaskUnderstanding.Requirement> requirements = new ArrayList<>();
        List<NaturalTaskReview.Coverage> requirementCoverage = new ArrayList<>();
        List<Binding> bindings = new ArrayList<>();
        for (var entry : actualEntries) {
            Integer replacementIndex = replacements.get(entry.reqId());
            bindings.add(new Binding(entry.reqId(), requirements.size(), replacementIndex));
            if (replacementIndex != null) {
                Change replacement = changes.get(replacementIndex);
                // 不自动把旧evidence并入新解释，否则会替writer补上实际未保留的题答来源。
                append(requirements, requirementCoverage, NaturalPromptRequirementRegistry.requirement(replacement.requirement()), replacement.bodyQuotes());
            } else {
                Reference reference = byId.get(entry.reqId());
                if (reference == null) throw invalid("正文引用遗漏已登记原要求");
                append(requirements, requirementCoverage, entry.requirement(), reference.bodyQuotes());
            }
        }
        for (int changeIndex = 0; changeIndex < changes.size(); changeIndex++) {
            Change change = changes.get(changeIndex);
            if (change.replacesId() == null) {
                // 新增编号由服务端规范化顺序发放；既有项含替换都保留原ID，不允许模型覆盖其他项。
                bindings.add(new Binding("r" + requirements.size(), requirements.size(), changeIndex));
                append(requirements, requirementCoverage, NaturalPromptRequirementRegistry.requirement(change.requirement()), change.bodyQuotes());
            }
        }
        List<NaturalTaskUnderstanding.Addition> canonicalAdditions = new ArrayList<>();
        List<NaturalTaskReview.Coverage> additionCoverage = new ArrayList<>();
        for (var addition : added) {
            additionCoverage.add(new NaturalTaskReview.Coverage(canonicalAdditions.size(), NaturalPromptRequirementRegistry.bounded(addition.bodyQuotes(), 16)));
            canonicalAdditions.add(new NaturalTaskUnderstanding.Addition(addition.text(), addition.benefit(), addition.gap()));
        }
        List<NaturalTaskUnderstanding.OpenDecision> decisions = new ArrayList<>();
        for (var decision : pending) {
            if (decision.kind() == null) throw invalid("未决信息缺少类型");
            decisions.add(new NaturalTaskUnderstanding.OpenDecision(decision.issue(), decision.impact(), "", decision.kind()));
        }
        var task = new NaturalTaskUnderstanding(goal, deliverable, requirements, canonicalAdditions, decisions,
                new NaturalTaskReview(inputReadiness, requirementCoverage, additionCoverage, List.of())).resolve(input);
        return new Normalized(new NaturalPromptDraft(optimizedPrompt.strip(), NaturalPromptDraft.Framework.valueOf(framework.strip()), metadata, task), bindings);
    }

    /** 下标只由实际规范化顺序形成；引文必须来自writer，最终substring核对仍交给原Review。 */
    private static void append(List<NaturalTaskUnderstanding.Requirement> requirements, List<NaturalTaskReview.Coverage> coverage,
            NaturalTaskUnderstanding.Requirement requirement, List<String> quotes) {
        coverage.add(new NaturalTaskReview.Coverage(requirements.size(), NaturalPromptRequirementRegistry.bounded(quotes, 16)));
        requirements.add(requirement);
    }

    private static ProviderException invalid(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false);
    }
}
