package com.promptoptimizer.provider.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 53独立的严格要求准备wire；不接收scope字段，也不制造内部动作来适配旧DTO。
 * 来源角色只核对任务目标的用户依据，不推断任务时态、充分性或语义一致性。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalPromptRequirementsOnlyPreparation(List<NaturalPromptPhasePayloads.Requirement> requirements) {
    public NaturalPromptRequirementsOnlyPreparation {
        requirements = requirements == null ? null : List.copyOf(requirements);
    }

    /** 复用完整来源、全部实际题答及容量校验后编号；准备不形成最终goal或回应范围。 */
    public Registered resolve(NaturalPromptInput input) {
        var original = NaturalPromptRequirementRegistry.bounded(requirements, 256).stream()
                .map(NaturalPromptRequirementRegistry::requirement).toList();
        if (original.isEmpty()) throw NaturalPromptRequirementRegistry.invalid("要求准备列表为空");
        // 直接复用原要求校验，不创建临时goal、scope或充分性状态来迁就完整task形状。
        var checked = NaturalTaskUnderstanding.resolveRequirements(input, original);
        NaturalTaskReview.validateRequirementSources(checked, input);
        validateUserGoals(checked);
        List<NaturalPromptRequirementRegistry.Entry> entries = new ArrayList<>();
        for (var requirement : checked) {
            entries.add(new NaturalPromptRequirementRegistry.Entry("r" + entries.size(), requirement.text(), requirement.dimensions(),
                    requirement.evidence().stream().map(evidence -> new NaturalPromptPhasePayloads.SourceEvidence(evidence.sourceId(), evidence.quote())).toList()));
        }
        return new Registered(entries);
    }

    /**
     * 原scope对任务委托要求用户来源；53将同一角色保护落实到已有GOAL维度，并在writer补正后重验。
     * 仅接收已resolve的记录；没有GOAL时不新增门控，纯背景/未指定动作与原advisory路径一致。
     * 对象和材料条件仍允许PROJECT/ASSISTANT出处，不根据自由文本猜测目标角色。
     */
    public static void validateUserGoals(NaturalTaskUnderstanding task) {
        validateUserGoals(task.requirements());
    }

    /** 准备和writer最终记录共用同一来源角色检查，不凭自由文本自动识别或增加目标。 */
    private static void validateUserGoals(List<NaturalTaskUnderstanding.Requirement> requirements) {
        for (var requirement : requirements) {
            if (!requirement.dimensions().contains(NaturalTaskUnderstanding.Dimension.GOAL)) continue;
            boolean user = requirement.evidence().stream().anyMatch(evidence -> switch (evidence.origin()) {
                case ORIGINAL_REQUEST, USER_CONVERSATION, BOUND_PLAN_ANSWER -> true;
                case PROJECT_CONTEXT, ASSISTANT_CONVERSATION -> false;
            });
            if (!user) throw NaturalPromptRequirementRegistry.invalid("任务目标要求缺少用户来源");
        }
    }

    /** 同请求的只读编号表；与旧Prepared分离，不包含scope、事实授权或充分性状态。 */
    public record Registered(List<NaturalPromptRequirementRegistry.Entry> requirements) {
        public Registered {
            requirements = NaturalPromptRequirementRegistry.bounded(requirements, 256);
            if (requirements.isEmpty()) throw NaturalPromptRequirementRegistry.invalid("要求准备结果为空");
            for (int index = 0; index < requirements.size(); index++) {
                if (!("r" + index).equals(requirements.get(index).reqId())) throw NaturalPromptRequirementRegistry.invalid("要求编号不是本次服务端顺序");
            }
        }
    }
}
