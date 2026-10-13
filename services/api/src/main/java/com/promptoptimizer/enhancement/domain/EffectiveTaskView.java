package com.promptoptimizer.enhancement.domain;

import com.promptoptimizer.provider.domain.ExampleContract;
import com.promptoptimizer.provider.domain.RequirementBoundaryContract;
import com.promptoptimizer.template.domain.TaskIntent;
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
        return new ModelInput(intent, executionRequirements.stream().map(requirement -> new ModelRequirement(
                requirement.questionId(), requirement.subject(), requirement.statement(), requirement.destination())).toList(),
                pendingDecisions, examples, requirementBoundaries().guidance(), meaning().anchors());
    }

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
            List<ConfirmedPlanDecision> pendingDecisions, ExampleContract examples, String requirementBoundaryGuidance,
            List<com.promptoptimizer.provider.domain.TaskMeaningContract.Anchor> userMeaningAnchors) { }

    /** 模型只需执行表述和责任段；已解决原题与原答案保留在服务端，不再重复灌入载荷。 */
    public record ModelRequirement(String questionId, String subject, String statement, PromptSectionType destination) { }
}
