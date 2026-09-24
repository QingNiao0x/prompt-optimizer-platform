package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.api.EnhancementOptions;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
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
        List<PlanningFactCard> planningFacts
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
