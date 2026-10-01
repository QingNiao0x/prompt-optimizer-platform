package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.template.domain.PromptTemplate;

import java.util.List;

/**
 * 统一模型适配层接收的提示词增强请求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record EnhancementProviderRequest(
        String rawPrompt,
        ContextSnapshot context,
        PromptTemplate template,
        List<String> ambiguities,
        List<PlanAnswer> planAnswers,
        boolean planConfirmed,
        List<String> constraints,
        List<ConversationMessage> conversationHistory,
        EnhancementOptions options,
        String model,
        List<PlanningFactCard> planningFacts,
        List<ConfirmedPlanDecision> confirmedDecisions
) {

    /**
     * 对列表字段做防御性拷贝，避免外部修改请求数据。
     */
    public EnhancementProviderRequest {
        ambiguities = List.copyOf(ambiguities);
        planAnswers = List.copyOf(planAnswers);
        constraints = List.copyOf(constraints);
        conversationHistory = List.copyOf(conversationHistory);
        planningFacts = planningFacts == null ? List.of() : List.copyOf(planningFacts);
        confirmedDecisions = confirmedDecisions == null ? List.of() : List.copyOf(confirmedDecisions);
    }

    /** 兼容尚未传递结构化决定的旧适配器；直接增强默认没有决定。 */
    public EnhancementProviderRequest(String rawPrompt, ContextSnapshot context, PromptTemplate template,
            List<String> ambiguities, List<PlanAnswer> planAnswers, boolean planConfirmed,
            List<String> constraints, List<ConversationMessage> conversationHistory,
            EnhancementOptions options, String model, List<PlanningFactCard> planningFacts) {
        this(rawPrompt, context, template, ambiguities, planAnswers, planConfirmed, constraints,
                conversationHistory, options, model, planningFacts, List.of());
    }

    /** 兼容尚未传递计划事实卡片的现有调用方。 */
    public EnhancementProviderRequest(
            String rawPrompt,
            ContextSnapshot context,
            PromptTemplate template,
            List<String> ambiguities,
            List<PlanAnswer> planAnswers,
            boolean planConfirmed,
            List<String> constraints,
            List<ConversationMessage> conversationHistory,
            EnhancementOptions options,
            String model
    ) {
        this(rawPrompt, context, template, ambiguities, planAnswers, planConfirmed,
                constraints, conversationHistory, options, model, List.of());
    }

    /**
     * 兼容计划模式上线前的测试和适配调用。
     */
    public EnhancementProviderRequest(
            String rawPrompt,
            ContextSnapshot context,
            PromptTemplate template,
            List<String> ambiguities,
            List<String> constraints,
            List<ConversationMessage> conversationHistory,
            EnhancementOptions options,
            String model
    ) {
        this(
                rawPrompt,
                context,
                template,
                ambiguities,
                List.of(),
                false,
                constraints,
                conversationHistory,
                options,
                model,
                List.of()
        );
    }

    /**
     * 兼容计划模式上线前的测试和适配调用。
     */
    public EnhancementProviderRequest(
            String rawPrompt,
            ContextSnapshot context,
            PromptTemplate template,
            List<String> ambiguities,
            List<PlanAnswer> planAnswers,
            boolean planConfirmed,
            List<String> constraints,
            List<ConversationMessage> conversationHistory,
            EnhancementOptions options
    ) {
        this(
                rawPrompt,
                context,
                template,
                ambiguities,
                planAnswers,
                planConfirmed,
                constraints,
                conversationHistory,
                options,
                null,
                List.of()
        );
    }

    /**
     * 兼容计划模式上线前的测试和适配调用。
     */
    public EnhancementProviderRequest(
            String rawPrompt,
            ContextSnapshot context,
            PromptTemplate template,
            List<String> ambiguities,
            List<String> constraints,
            List<ConversationMessage> conversationHistory,
            EnhancementOptions options
    ) {
        this(
                rawPrompt,
                context,
                template,
                ambiguities,
                List.of(),
                false,
                constraints,
                conversationHistory,
                options,
                null,
                List.of()
        );
    }
}
