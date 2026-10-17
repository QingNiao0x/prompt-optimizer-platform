package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.EffectiveTaskView.ExecutionRequirement;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.ExampleContract;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.util.List;

/**
 * 在身份、Plan 绑定和安全事实过滤之后，统一构建提问、检索、模型与组装所用的任务状态。
 * 复用既有部分确认、旧状态和交付识别器，不再建立另一套任务分类或答案来源。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class EffectiveTaskViewFactory {
    private EffectiveTaskViewFactory() { }

    /** 兼容内部旧请求；生产请求直接复用视图，不重新解释确认，也不开放客户端自报状态。 */
    public static EffectiveTaskView forProvider(EnhancementProviderRequest request) {
        if (request.taskView() != null) return request.taskView();
        List<PlanAnswer> answers = !request.planConfirmed() ? List.of() : !request.planAnswers().isEmpty()
                ? request.planAnswers() : request.confirmedDecisions().stream()
                    .map(decision -> new PlanAnswer(decision.questionId(), decision.question(), decision.answer())).toList();
        return create(request.rawPrompt(), request.template().code(), answers, request.planningFacts(),
                Boolean.TRUE.equals(request.options().includeExamples()));
    }

    /** 直接增强的答案列表必须为空；答案已由调用方通过服务端会话规范化。 */
    public static EffectiveTaskView create(String raw, TemplateCode template, List<PlanAnswer> answers,
            List<PlanningFactCard> facts, boolean includeExamples) {
        String original = raw == null ? "" : raw;
        var decisions = ConfirmedDecisionSet.from(answers);
        String current = ResolvedPlanState.from(decisions, original)
                .reconcile(PlatformConstraintRenderer.taskInput(original));
        var known = decisions.knownDecisions();
        var requirements = decisions.decisions().stream().flatMap(decision -> requirements(decision, known).stream()).toList();
        // 示例只读取用户指令及有效答案，附件中的“提供示例”不具有开关权限。
        String exampleInput = current + requirements.stream().map(ExecutionRequirement::statement)
                .collect(java.util.stream.Collectors.joining("\n", "\n", ""));
        return new EffectiveTaskView(original, current,
                TaskIntentResolver.resolve(template, original, decisions.knownDecisions()), decisions.decisions(),
                requirements, decisions.pendingDecisions(), facts == null ? List.of() : facts,
                ExampleContract.from(exampleInput, includeExamples));
    }

    /** 独立事实、委托和未确认边界按原句投影；真正裸短答仍绑定服务端问题的完整对象。 */
    private static List<ExecutionRequirement> requirements(ConfirmedPlanDecision decision, List<ConfirmedPlanDecision> known) {
        var independent = PlanAnswerSemantics.independentStatements(decision.answer());
        if (!independent.isEmpty()) return independent.stream().map(statement -> {
            PromptSectionType destination = switch (statement.kind()) {
                case CURRENT_STATE -> PromptSectionType.BACKGROUND;
                case DELEGATION, LIMITED_DELEGATION -> PromptSectionType.TASK;
                case NON_CONFIRMATION -> PromptSectionType.CONSTRAINTS;
            };
            // 原问题 ID 与文案保留来源；subject 不把回答自身的主语替换成未作答的参数名。
            String subject = switch (statement.kind()) {
                case CURRENT_STATE -> "用户说明";
                case DELEGATION -> "委托要求";
                // 局部指令照常执行，但不能据同一个来源题ID授权另一个尚未选择的参数。
                case LIMITED_DELEGATION -> "局部委托要求";
                case NON_CONFIRMATION -> "未确认边界";
            };
            return new ExecutionRequirement(decision.questionId(), decision.question(), subject,
                    statement.text(), statement.text(), destination);
        }).toList();
        // 未进入独立句投影的回答复用原有部分确认分类，避免把已知现状改送到任务段。
        return known.stream().filter(value -> java.util.Objects.equals(value.questionId(), decision.questionId())
                && value.question().equals(decision.question())).findFirst().map(value -> List.of(requirement(value)))
                .orElseGet(List::of);
    }

    /** 短答案补全实际问题对象；完整业务表述原样保留，避免“地区：采用年龄标准化”的错标签。 */
    private static ExecutionRequirement requirement(ConfirmedPlanDecision decision) {
        String subject = PlanDecisionIdentity.executionSubject(decision.question());
        String answer = decision.answer().strip();
        boolean complete = answer.matches("(?s).*(?:采用|使用|按照|按\\s*[^，。；;]+|来自|提供|交付|比较|分解|要求|必须|不得|不能|保留|不允许|不需要|不提供|\\b(?:use|must|return|provide)\\b).*" )
                || answer.matches("(?s)^[^：:]{2,80}[：:].+");
        // 用户已经给出对象或执行动作时，不再套上问题标签；方向短答也改为可执行指令。
        String statement = decision.question().contains("排序")
                && answer.matches("(?:升序|降序)[。.]?") ? "按" + answer.replaceAll("[。.]$", "") + "排序。"
                : complete ? answer : subject + "：" + answer;
        PromptSectionType destination = decision.scope() == ConfirmedPlanDecision.Scope.CURRENT_STATE
                ? PromptSectionType.BACKGROUND
                : TaskIntentResolver.overallDeliveryQuestion(decision.question())
                || decision.topic().startsWith("输出") || decision.topic().startsWith("交付")
                ? PromptSectionType.OUTPUT : PromptSectionType.TASK;
        return new ExecutionRequirement(decision.questionId(), decision.question(), subject, answer, statement, destination);
    }

    /** 旧择一请求用已确认子项替代；总体未决回答仍不能被当成全部确认。 */
    static String statementFor(ConfirmedPlanDecision decision) {
        String known = PlanAnswerSemantics.confirmedPart(decision.answer());
        return requirement(new ConfirmedPlanDecision(decision.questionId(), decision.question(), decision.topic(),
                decision.scope(), known)).statement() + "。";
    }
}
