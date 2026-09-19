package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.application.ContextAnalyzer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.api.OptimizationRequest;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.policy.application.ConstraintCompleter;
import com.promptoptimizer.policy.application.ProtectedContextFilter;
import com.promptoptimizer.provider.application.PromptEnhancementProvider;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.application.PromptTemplateRegistry;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * 默认提示词增强编排器。
 *
 * <p>按“上下文分析 → 模糊点识别 → 模板选择 → 约束补全 → Provider 生成”的顺序执行，
 * 确保真实模型和 Mock 模型共享同一套业务规则。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class DefaultEnhancementOrchestrator implements EnhancementOrchestrator {

    private final ContextAnalyzer contextAnalyzer;
    private final AmbiguityDetector ambiguityDetector;
    private final PromptTemplateRegistry templateRegistry;
    private final ConstraintCompleter constraintCompleter;
    private final PromptEnhancementProvider enhancementProvider;
    private final OptimizationResultAssembler resultAssembler;
    private final ProtectedContextFilter protectedContextFilter;
    private final PlanningSessionService planningSessionService;
    private final SensitiveValueDetector sensitiveValueDetector;
    private final Clock clock;

    @Autowired
    public DefaultEnhancementOrchestrator(
            ContextAnalyzer contextAnalyzer,
            AmbiguityDetector ambiguityDetector,
            PromptTemplateRegistry templateRegistry,
            ConstraintCompleter constraintCompleter,
            PromptEnhancementProvider enhancementProvider,
            OptimizationResultAssembler resultAssembler,
            ProtectedContextFilter protectedContextFilter,
            PlanningSessionService planningSessionService
    ) {
        this(
                contextAnalyzer,
                ambiguityDetector,
                templateRegistry,
                constraintCompleter,
                enhancementProvider,
                resultAssembler,
                protectedContextFilter,
                planningSessionService,
                Clock.systemUTC()
        );
    }

    DefaultEnhancementOrchestrator(
            ContextAnalyzer contextAnalyzer,
            AmbiguityDetector ambiguityDetector,
            PromptTemplateRegistry templateRegistry,
            ConstraintCompleter constraintCompleter,
            PromptEnhancementProvider enhancementProvider,
            Clock clock
    ) {
        this(
                contextAnalyzer,
                ambiguityDetector,
                templateRegistry,
                constraintCompleter,
                enhancementProvider,
                new OptimizationResultAssembler(),
                new ProtectedContextFilter(),
                new PlanningSessionService(
                        new InMemoryPlanningSessionStore(clock),
                        contextAnalyzer,
                        new ProtectedContextFilter(),
                        clock
                ),
                clock
        );
    }

    DefaultEnhancementOrchestrator(
            ContextAnalyzer contextAnalyzer,
            AmbiguityDetector ambiguityDetector,
            PromptTemplateRegistry templateRegistry,
            ConstraintCompleter constraintCompleter,
            PromptEnhancementProvider enhancementProvider,
            OptimizationResultAssembler resultAssembler,
            ProtectedContextFilter protectedContextFilter,
            Clock clock
    ) {
        this(
                contextAnalyzer,
                ambiguityDetector,
                templateRegistry,
                constraintCompleter,
                enhancementProvider,
                resultAssembler,
                protectedContextFilter,
                new PlanningSessionService(
                        new InMemoryPlanningSessionStore(clock),
                        contextAnalyzer,
                        protectedContextFilter,
                        clock
                ),
                clock
        );
    }

    DefaultEnhancementOrchestrator(
            ContextAnalyzer contextAnalyzer,
            AmbiguityDetector ambiguityDetector,
            PromptTemplateRegistry templateRegistry,
            ConstraintCompleter constraintCompleter,
            PromptEnhancementProvider enhancementProvider,
            OptimizationResultAssembler resultAssembler,
            ProtectedContextFilter protectedContextFilter,
            PlanningSessionService planningSessionService,
            Clock clock
    ) {
        this.contextAnalyzer = contextAnalyzer;
        this.ambiguityDetector = ambiguityDetector;
        this.templateRegistry = templateRegistry;
        this.constraintCompleter = constraintCompleter;
        this.enhancementProvider = enhancementProvider;
        this.resultAssembler = resultAssembler;
        this.protectedContextFilter = protectedContextFilter;
        this.planningSessionService = planningSessionService;
        this.sensitiveValueDetector = new SensitiveValueDetector();
        this.clock = clock;
    }

    /**
     * 按固定顺序完成上下文分析、模糊点识别、模板选择、约束补全和模型生成。
     */
    @Override
    public OptimizationResult optimize(OptimizationRequest request) {
        long startedAt = clock.millis();
        validateTextInputs(request);
        boolean planConfirmed = request.planConfirmation() != null;
        PlanningSessionService.ConfirmedPlan confirmedPlan = planningSessionService.confirm(
                request.rawPrompt(),
                request.context().customDescription(),
                request.conversationHistory(),
                request.planConfirmation()
        );
        ProtectedContextFilter.FilteredContext filteredContext = protectedContextFilter.filter(
                request.context(),
                request.permissionPolicy()
        );
        List<PlanAnswer> planAnswers = confirmedPlan.answers();
        String contextQuery = buildContextQuery(request.rawPrompt(), planAnswers);
        ContextSnapshot context = planningSessionService.reusableContext(
                        confirmedPlan,
                        filteredContext.request(),
                        contextQuery
                )
                .orElseGet(() -> protectedContextFilter.attachReport(
                        contextAnalyzer.analyze(filteredContext.request(), contextQuery),
                        filteredContext
                ));
        List<String> ambiguities = planConfirmed ? List.of() : ambiguityDetector.detect(request.rawPrompt());
        PromptTemplate template = templateRegistry.resolve(
                request.enhancement().templateCode(),
                request.rawPrompt()
        );
        List<String> constraints = constraintCompleter.complete(
                context,
                request.permissionPolicy(),
                Boolean.TRUE.equals(request.enhancement().includePermissionBoundaries()),
                template.code()
        );
        List<ConversationMessage> conversation = Boolean.TRUE.equals(request.enhancement().includeConversationHistory())
                ? request.conversationHistory()
                : List.of();

        EnhancementProviderResponse providerResponse = enhancementProvider.enhance(new EnhancementProviderRequest(
                request.rawPrompt(),
                context,
                template,
                ambiguities,
                planAnswers,
                planConfirmed,
                constraints,
                conversation,
                request.enhancement()
        ));

        return resultAssembler.assemble(
                providerResponse,
                context,
                template,
                ambiguities,
                planAnswers,
                planConfirmed,
                constraints,
                Boolean.TRUE.equals(request.enhancement().includeExamples()),
                Math.max(0, clock.millis() - startedAt)
        );
    }

    private void validateTextInputs(OptimizationRequest request) {
        rejectCredential(request.rawPrompt());
        rejectCredential(request.context().customDescription());
        request.conversationHistory().forEach(message -> rejectCredential(message.content()));
        if (request.planConfirmation() != null) {
            request.planConfirmation().answers().forEach(answer -> {
                if (answer != null) {
                    rejectCredential(answer.question());
                    rejectCredential(answer.answer());
                }
            });
        }
    }

    private String buildContextQuery(String rawPrompt, List<PlanAnswer> answers) {
        StringBuilder query = new StringBuilder(rawPrompt.trim());
        answers.forEach(answer -> query
                .append('\n')
                .append(answer.question())
                .append('\n')
                .append(answer.answer()));
        return query.toString();
    }

    private void rejectCredential(String value) {
        if (sensitiveValueDetector.containsCredential(value)) {
            throw new InvalidOptimizationRequestException("输入中疑似包含真实凭据，请移除后重试。");
        }
    }
}
