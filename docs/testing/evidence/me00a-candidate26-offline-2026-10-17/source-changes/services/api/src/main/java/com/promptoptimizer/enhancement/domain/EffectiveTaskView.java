package com.promptoptimizer.enhancement.domain;

import com.promptoptimizer.provider.domain.ExampleContract;
import com.promptoptimizer.provider.domain.RequirementBoundaryContract;
import com.promptoptimizer.template.domain.TaskIntent;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 本次优化的内部有效任务视图：保留原始需求，另提供已绑定决定更新后的执行输入。
 * 不接受客户端自报确认，不持久化完整文件，不把来源事实或建议提升为用户选择。
 *
 * @param originalPrompt 不可改写的用户基准，用于保真和追溯
 * @param effectivePrompt 仅更新可证明失效的旧选择，保留独立未知、条件、授权与新冲突
 * @param intent 沿用共享 TaskIntentResolver 的交付识别结果
 * @param confirmedDecisions 服务端绑定后的原始决定，含部分确认与未决状态
 * @param executionRequirements 已确认子项的执行表述，不能复制未选候选或整份答卷
 * @param pendingDecisions 真正未定的子项，不被相似主题的确认覆盖
 * @param facts 安全且与本次任务相关的事实卡，来源用途仍须核对
 * @param examples 下游示例要求及禁例、严格格式的优先级
 * @author QingNiao
 * @since 0.1.0
 */
public record EffectiveTaskView(String originalPrompt, String effectivePrompt, TaskIntent intent,
        List<ConfirmedPlanDecision> confirmedDecisions, List<ExecutionRequirement> executionRequirements,
        List<ConfirmedPlanDecision> pendingDecisions, List<PlanningFactCard> facts, ExampleContract examples) {
    /** 防御性复制；视图只存在于一次服务端请求，不跨任务缓存。 */
    public EffectiveTaskView {
        confirmedDecisions = List.copyOf(confirmedDecisions);
        executionRequirements = List.copyOf(executionRequirements);
        pendingDecisions = List.copyOf(pendingDecisions);
        facts = List.copyOf(facts);
    }

    /** 保留完整来源问题以核对对象与条件；statement 才是面向执行者的要求。 */
    public record ExecutionRequirement(String questionId, String question, String subject,
            String value, String statement, PromptSectionType destination) { }

    /** 模型只接收执行投影；不再重复发送原始旧提问或安全事实卡，计划阶段也不扩张资料预算。 */
    public ModelInput modelInput() {
        return new ModelInput(intent, modelRequirements(), pendingDecisions.stream().map(decision ->
                new ModelPendingDecision(decision.questionId(), decision.question(), decision.topic(), decision.scope(),
                        decision.answer(), decision.source(), executionRequirements.stream().anyMatch(requirement ->
                            java.util.Objects.equals(requirement.questionId(), decision.questionId())
                                    && requirement.subject().equals("委托要求")))).toList(),
                examples, requirementBoundaries().guidance(), meaning().anchors());
    }

    /** 仅合并自身已命名的独立事实、委托与边界；普通题答继续按原绑定问题隔离。 */
    private List<ModelRequirement> modelRequirements() {
        var groups = new LinkedHashMap<RequirementKey, List<ExecutionRequirement>>();
        for (var requirement : executionRequirements) {
            boolean selfContained = List.of("用户说明", "委托要求", "局部委托要求", "未确认边界").contains(requirement.subject());
            var key = new RequirementKey(requirement.subject(), requirement.statement(), requirement.destination(),
                    selfContained ? null : requirement.questionId());
            groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(requirement);
        }
        return groups.values().stream().map(values -> {
            var first = values.getFirst();
            // 一次表达不丢来源；原始问题、全部回答及总体未决状态仍在服务端视图中。
            var ids = values.stream().map(ExecutionRequirement::questionId).filter(java.util.Objects::nonNull).distinct().toList();
            return new ModelRequirement(first.questionId(), first.subject(), first.statement(), first.destination(), ids);
        }).toList();
    }

    private record RequirementKey(String subject, String statement, PromptSectionType destination, String questionId) { }

    /** 受众和研究主题保留各自原文来源，不让模型将写作对象升级为研究样本。 */
    public com.promptoptimizer.provider.domain.TaskMeaningContract meaning() {
        return com.promptoptimizer.provider.domain.TaskMeaningContract.from(effectivePrompt, confirmedDecisions);
    }

    /** 各环节从同一有效输入取得禁止、选择条件和独立参数边界，不读取旧任务或客户端自报状态。 */
    public RequirementBoundaryContract requirementBoundaries() {
        return RequirementBoundaryContract.from(effectivePrompt, confirmedDecisions, facts);
    }

    /** 各模型适配器共用同一业务投影，不按供应商另行解释用户选择。 */
    public record ModelInput(TaskIntent intent, List<ModelRequirement> executionRequirements,
            List<ModelPendingDecision> pendingDecisions, ExampleContract examples, String requirementBoundaryGuidance,
            List<com.promptoptimizer.provider.domain.TaskMeaningContract.Anchor> userMeaningAnchors) { }

    /** 委托拟议设计和既有参数未确认是两个独立状态；designDelegated 不确认取值或消除未决条件。 */
    public record ModelPendingDecision(String questionId, String question, String topic, ConfirmedPlanDecision.Scope scope,
            String answer, ConfirmedPlanDecision.Source source, boolean designDelegated) { }

    /** 共同表述只发送一次，并保留全部绑定问题 ID；普通独立对象不因文字相同而归并。 */
    public record ModelRequirement(String questionId, String subject, String statement, PromptSectionType destination,
            List<String> sourceQuestionIds) {
        public ModelRequirement {
            sourceQuestionIds = List.copyOf(sourceQuestionIds);
        }

        /** 兼容内部旧构造方式；没有绑定 ID 时不伪造来源。 */
        public ModelRequirement(String questionId, String subject, String statement, PromptSectionType destination) {
            this(questionId, subject, statement, destination, questionId == null ? List.of() : List.of(questionId));
        }
    }
}
