package com.promptoptimizer.provider.domain;

import java.util.List;

/**
 * 69仅收紧内部诊断标签的来源角色，不重写正文、要求或出处，不进行语义通过判定。
 * 材料条件已经有其他维度且真实用户目标另有记录时，额外GOAL不能把材料提升为用户委托。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptDiagnosticRoles {
    public static final String VERSION = "69";

    private NaturalPromptDiagnosticRoles() { }

    /**
     * 先按本次原始来源重新核验，拒绝模型自报origin；只去掉有明确替代维度的多余GOAL。
     * 没有真实用户目标、仅有GOAL的疑似委托项及来源/覆盖错误不在此修复，继续走原保护。
     */
    public static NaturalPromptDraft normalize(NaturalPromptDraft draft, NaturalPromptInput input) {
        if ((!VERSION.equals(input.strategyVersion()) && !NaturalPromptBodyFirstInstructions.WRITER_INPUT_VERSION.equals(input.strategyVersion()))
                || draft.taskUnderstanding() == null) return draft;
        var task = draft.taskUnderstanding().resolve(input);
        boolean hasUserGoal = task.requirements().stream().anyMatch(requirement ->
                requirement.dimensions().contains(NaturalTaskUnderstanding.Dimension.GOAL)
                        && requirement.evidence().stream().anyMatch(evidence -> user(evidence.origin())));
        if (!hasUserGoal) return draft;
        List<NaturalTaskUnderstanding.Requirement> requirements = task.requirements().stream().map(requirement -> {
            if (!requirement.dimensions().contains(NaturalTaskUnderstanding.Dimension.GOAL)
                    || requirement.dimensions().stream().noneMatch(dimension -> dimension != NaturalTaskUnderstanding.Dimension.GOAL)
                    || requirement.evidence().stream().anyMatch(evidence -> user(evidence.origin()))) return requirement;
            // 只处理来源已由服务端核验的材料/助手项；保持文字、出处、条目位置及全部正文覆盖引文。
            return new NaturalTaskUnderstanding.Requirement(requirement.text(), requirement.evidence(),
                    requirement.dimensions().stream().filter(dimension -> dimension != NaturalTaskUnderstanding.Dimension.GOAL).toList());
        }).toList();
        if (requirements.equals(task.requirements())) return draft;
        var normalized = new NaturalTaskUnderstanding(task.goal(), task.deliverable(), requirements,
                task.additions(), task.openDecisions(), task.review());
        return new NaturalPromptDraft(draft.optimizedPrompt(), draft.framework(), draft.provider(), normalized, draft.completedStages());
    }

    /** 来源身份由原始input绑定，助手和材料不能单凭标签取得用户身份。 */
    private static boolean user(NaturalTaskSource.Kind origin) {
        return origin == NaturalTaskSource.Kind.ORIGINAL_REQUEST || origin == NaturalTaskSource.Kind.USER_CONVERSATION
                || origin == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER;
    }
}
