package com.promptoptimizer.provider.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 保真诊断与编辑目标映射：同一首稿跨度保留多条独立诊断，但只执行一次局部替换。
 * 来源引文及模型判断不因归组而成为新的事实或授权。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptFidelityTargets {
    /** 与既有Review的16项容量保持一致；归组不能借重复跨度扩大诊断总容量。 */
    private static final int MAX_DIAGNOSES = 16;

    /**
     * 逐条校验全部诊断，再将完全相同的唯一首稿片段归为一个编辑目标。
     * 目标按诊断首次出现顺序编号，诊断索引保留原顺序；部分重叠仍由既有Review拒绝，不扩展为更大跨度。
     */
    public static List<Target> resolve(NaturalPromptInput input, NaturalPromptDraft draft,
            NaturalPromptFidelityFindings findings) {
        if (input == null || draft == null || findings == null || findings.issues() == null) {
            throw invalid("保真编辑目标缺少输入、首稿或诊断");
        }
        if (findings.issues().size() > MAX_DIAGNOSES) {
            throw invalid("保真诊断超出容量");
        }
        var grouped = new LinkedHashMap<String, List<Integer>>();
        for (int index = 0; index < findings.issues().size(); index++) {
            var finding = findings.issues().get(index);
            if (finding == null) throw invalid("保真诊断条目无效");
            // 每一条都独立核对type、effect及来源，不能因同跨度只检查第一条而丢失证据保护。
            new NaturalPromptFidelityFindings(List.of(finding)).validate(input, draft);
            grouped.computeIfAbsent(finding.bodyQuote(), ignored -> new ArrayList<>()).add(index);
        }
        var targets = new ArrayList<Target>(grouped.size());
        var identities = new ArrayList<NaturalPromptFidelityReview.Issue>(grouped.size());
        for (var entry : grouped.entrySet()) {
            targets.add(new Target(targets.size(), entry.getKey(), entry.getValue()));
            var representative = findings.issues().get(entry.getValue().getFirst());
            // 代表条目仅复用一次跨度操作；其余诊断及来源继续完整保留在findings和diagnosisIndices中。
            identities.add(issue(representative, entry.getKey()));
        }
        new NaturalPromptFidelityReview(identities, identities.isEmpty() ? null : draft.taskUnderstanding()).apply(input, draft);
        return List.copyOf(targets);
    }

    /**
     * 要求每个已解析目标恰好有一次修订，允许编辑顺序不同但拒绝遗漏、重复或越界索引。
     * 替换范围和完整诊断由服务端重新解析，修订方只能提供replacement及更新后的任务记录。
     */
    public static NaturalPromptDraft applyRepair(NaturalPromptInput input, NaturalPromptDraft draft,
            NaturalPromptFidelityFindings findings, Repair repair) {
        List<Target> targets = resolve(input, draft, findings);
        if (repair == null || repair.edits() == null || repair.edits().size() != targets.size()) {
            throw invalid("保真修订未完整对应编辑目标");
        }
        var replacements = new String[targets.size()];
        var supplied = new boolean[targets.size()];
        for (Edit edit : repair.edits()) {
            if (edit == null || edit.targetIndex() == null || edit.targetIndex() < 0
                    || edit.targetIndex() >= targets.size() || supplied[edit.targetIndex()]) {
                throw invalid("保真修订目标索引无效或重复");
            }
            supplied[edit.targetIndex()] = true;
            replacements[edit.targetIndex()] = edit.replacement();
        }
        var patches = new ArrayList<NaturalPromptFidelityReview.Issue>(targets.size());
        for (Target target : targets) {
            if (!supplied[target.index()]) throw invalid("保真修订遗漏编辑目标");
            var representative = findings.issues().get(target.diagnosisIndices().getFirst());
            patches.add(issue(representative, replacements[target.index()]));
        }
        // 每个不同跨度只出现一次；最终整稿仍复用原始来源、安全、全部题答及覆盖检查。
        return new NaturalPromptFidelityReview(patches, repair.taskUnderstanding()).apply(input, draft);
    }

    /** 编辑目标只持有首稿片段和全部原诊断索引，不替代原诊断的类别、来源或交付影响。 */
    public record Target(int index, String bodyQuote, List<Integer> diagnosisIndices) {
        /** 服务端生成的索引列表不可变，避免PATCH输入组装后静默改变诊断归属。 */
        public Target {
            diagnosisIndices = List.copyOf(diagnosisIndices);
        }
    }

    /** 修订仅提交各目标的替换片段和最终任务记录，不能自行提交新的来源或正文范围。 */
    public record Repair(List<Edit> edits, NaturalTaskUnderstanding taskUnderstanding) {
        /** 防御性复制修订列表；缺省和空条目由applyRepair按统一协议异常拒绝。 */
        public Repair {
            edits = edits == null ? null : Collections.unmodifiableList(new ArrayList<>(edits));
        }
    }

    /** targetIndex仅引用本次服务端解析结果；用可空类型显式拒绝模型漏填索引，replacement允许明确删除。 */
    public record Edit(Integer targetIndex, String replacement) { }

    /** 用已独立校验的代表证据复用既有单次跨度操作，不声称其他诊断无效或语义已获证明。 */
    private static NaturalPromptFidelityReview.Issue issue(NaturalPromptFidelityFindings.Finding representative,
            String replacement) {
        return new NaturalPromptFidelityReview.Issue(representative.type(), representative.sourceId(),
                representative.sourceQuote(), representative.bodyQuote(), replacement);
    }

    /** 协议异常只报告稳定边界，不包含原始诊断、正文或用户资料。 */
    private static ProviderException invalid(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false);
    }

    private NaturalPromptFidelityTargets() { }
}
