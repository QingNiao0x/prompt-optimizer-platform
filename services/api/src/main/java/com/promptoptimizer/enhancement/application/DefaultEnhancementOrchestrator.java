package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.application.ContextAnalyzer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.common.logging.LogCorrelation;
import com.promptoptimizer.common.logging.LogFields;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.api.OptimizationRequest;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.identity.application.CurrentActor;
import com.promptoptimizer.policy.application.ConstraintCompleter;
import com.promptoptimizer.policy.application.ProtectedContextFilter;
import com.promptoptimizer.provider.application.PromptEnhancementProvider;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.application.PromptTemplateRegistry;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultEnhancementOrchestrator.class);

    private final ContextAnalyzer contextAnalyzer;
    private final AmbiguityDetector ambiguityDetector;
    private final PromptTemplateRegistry templateRegistry;
    private final ConstraintCompleter constraintCompleter;
    private final PromptEnhancementProvider enhancementProvider;
    private final OptimizationResultAssembler resultAssembler;
    private final ProtectedContextFilter protectedContextFilter;
    private final PlanningSessionService planningSessionService;
    private final SensitiveValueDetector sensitiveValueDetector;
    private final ContextConflictDetector contextConflictDetector = new ContextConflictDetector();
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
            CurrentActor currentActor,
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
                        currentActor,
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
            CurrentActor currentActor,
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
                        currentActor,
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
        PlanningSessionService.ConfirmedPlan confirmedPlan = planningSessionService.confirm(
                request.rawPrompt(),
                request.context().customDescription(),
                request.conversationHistory(),
                request.planConfirmation()
        );
        boolean planConfirmed = confirmedPlan.bound();
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
        List<ConversationMessage> conversation = Boolean.TRUE.equals(request.enhancement().includeConversationHistory())
                ? request.conversationHistory()
                : List.of();
        List<ConversationMessage> ambiguityEvidence = new ArrayList<>(conversation);
        planAnswers.forEach(answer -> ambiguityEvidence.add(new ConversationMessage(
                "user", confirmedAnswerEvidence(answer)
        )));
        List<String> ambiguities = ambiguityDetector.detect(request.rawPrompt(), context, ambiguityEvidence);
        List<String> contextConflicts = contextConflictDetector.detect(context, planAnswers);
        if (!contextConflicts.isEmpty()) {
            List<String> combined = new ArrayList<>(ambiguities);
            contextConflicts.stream().filter(value -> !combined.contains(value))
                    .limit(Math.max(0, 8 - combined.size())).forEach(combined::add);
            ambiguities = List.copyOf(combined);
        }
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

        String workflowResourceId = request.planConfirmation() == null
                ? null
                : request.planConfirmation().planningContext() != null
                ? request.planConfirmation().planningContext().contextId()
                : request.planConfirmation().planId();
        String workflowNamespace = request.planConfirmation() == null
                ? "planning-context"
                : request.planConfirmation().planningContext() != null
                ? "planning-context"
                : "planning-plan";
        try (LogCorrelation.Scope ignored = LogCorrelation.bindWorkflow(workflowNamespace, workflowResourceId)) {
            try {
                EnhancementProviderResponse providerResponse = enhancementProvider.enhance(
                        new EnhancementProviderRequest(
                                request.rawPrompt(),
                                context,
                                template,
                                ambiguities,
                                planAnswers,
                                planConfirmed,
                                constraints,
                                conversation,
                                request.enhancement(),
                                request.model(),
                                confirmedPlan.planningContextDigest() == null
                                        ? List.of()
                                        : confirmedPlan.planningContextDigest().factCards()
                        )
                );
                long latencyMs = Math.max(0, clock.millis() - startedAt);
                if (providerResponse.mock()) {
                    ModelCallLogger.completed("prompt.optimize", providerResponse.provider(),
                            providerResponse.model(), "MOCK_PROVIDER", true, 1, 1, latencyMs, null);
                }
                OptimizationResult result = resultAssembler.assemble(
                        providerResponse,
                        context,
                        template,
                        ambiguities,
                        planAnswers,
                        planConfirmed,
                        constraints,
                        Boolean.TRUE.equals(request.enhancement().includeExamples()),
                        latencyMs,
                        request.rawPrompt(),
                        confirmedPlan.planningContextDigest() == null
                                ? List.of()
                                : confirmedPlan.planningContextDigest().factCards(),
                        confirmedPlan.planningContextDigest() == null
                                ? List.of()
                                : confirmedPlan.planningContextDigest().warnings()
                );
                LOGGER.info("event=optimization.completed requestId={} workflowId={} mock={} "
                                + "sections={} ambiguities={} durationMs={}",
                        LogFields.value(MDC.get("requestId")),
                        LogFields.value(MDC.get("workflowId")),
                        providerResponse.mock(),
                        result.sections().size(),
                        result.ambiguities().size(),
                        latencyMs);
                return result;
            } catch (RuntimeException exception) {
                LOGGER.error("event=optimization.failed requestId={} workflowId={} failureType={} durationMs={}",
                        LogFields.value(MDC.get("requestId")),
                        LogFields.value(MDC.get("workflowId")),
                        failureType(exception),
                        Math.max(0, clock.millis() - startedAt));
                throw exception;
            }
        }
    }

    /** 记录稳定的 Provider 故障分类或异常类型，不读取异常消息及上游响应正文。 */
    private String failureType(RuntimeException exception) {
        if (exception instanceof com.promptoptimizer.provider.domain.ProviderException providerException) {
            return providerException.getFailureType().name();
        }
        return LogFields.value(exception.getClass().getSimpleName());
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

    /** 将已确认回答转换为明确事实，避免问题中的问号令歧义检测忽略整句。 */
    private String confirmedAnswerEvidence(PlanAnswer answer) {
        String id = answer.questionId().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("region")) return "研究地区：" + answer.answer();
        if (id.contains("login") || id.contains("auth")) return "采用 " + answer.answer();
        return answer.question().replace("？", "").replace("?", "") + "：" + answer.answer();
    }

    private void rejectCredential(String value) {
        if (sensitiveValueDetector.containsCredential(value)) {
            throw new InvalidOptimizationRequestException("输入中疑似包含真实凭据，请移除后重试。");
        }
    }
}
