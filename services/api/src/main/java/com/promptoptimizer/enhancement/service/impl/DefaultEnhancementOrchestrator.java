package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.service.EnhancementOrchestrator;
import com.promptoptimizer.enhancement.service.PlanningSessionService;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.context.service.ContextAnalyzer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.common.logging.LogCorrelation;
import com.promptoptimizer.common.logging.LogFields;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.policy.service.ConstraintCompleter;
import com.promptoptimizer.policy.service.ProtectedContextFilter;
import com.promptoptimizer.provider.service.PromptEnhancementProvider;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.service.PromptTemplateRegistry;
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
    private PlatformModelCatalog modelCatalog;

    /** Spring 运行时注入模型目录，既有直接构造的单元测试仍可使用 Mock 默认模型。 */
    @Autowired(required = false)
    public void setModelCatalog(PlatformModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

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
                new ProtectedContextFilterImpl(),
                new PlanningSessionServiceImpl(
                        new InMemoryPlanningSessionStore(clock),
                        contextAnalyzer,
                        new ProtectedContextFilterImpl(),
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
                new PlanningSessionServiceImpl(
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
        String requestedModelId = request.modelId() == null ? "" : request.modelId().trim();
        if (planConfirmed && !requestedModelId.isBlank()
                && !requestedModelId.equals(confirmedPlan.modelId())) {
            throw new InvalidOptimizationRequestException("所选模型与已确认的计划不一致，请重新生成确认问题。");
        }
        String boundModelId = planConfirmed ? confirmedPlan.modelId() : requestedModelId;
        if (modelCatalog == null && boundModelId != null && !boundModelId.isBlank()) {
            throw new InvalidOptimizationRequestException("平台模型目录不可用，请稍后重试。");
        }
        var selectedModel = modelCatalog == null ? null : modelCatalog.resolve(boundModelId);
        String selectedModelId = selectedModel == null ? null : selectedModel.publicId();
        ProtectedContextFilter.FilteredContext filteredContext = protectedContextFilter.filter(
                request.context(),
                request.permissionPolicy()
        );
        List<PlanAnswer> planAnswers = confirmedPlan.answers();
        ConfirmedDecisionSet decisions = ConfirmedDecisionSet.from(planAnswers);
        String contextQuery = decisions.retrievalQuery(request.rawPrompt());
        ContextSnapshot context = com.promptoptimizer.common.logging.PipelineStageTiming.measure(
                "prompt.optimize", "context.analyze", selectedModelId, () -> planningSessionService.reusableContext(
                        confirmedPlan,
                        filteredContext.request(),
                        contextQuery
                )
                .orElseGet(() -> protectedContextFilter.attachReport(
                        contextAnalyzer.analyze(filteredContext.request(), contextQuery),
                        filteredContext
                )));
        List<ConversationMessage> conversation = Boolean.TRUE.equals(request.enhancement().includeConversationHistory())
                ? request.conversationHistory()
                : List.of();
        List<ConversationMessage> ambiguityEvidence = new ArrayList<>(conversation);
        planAnswers.stream().filter(answer -> decisions.decisions().stream().anyMatch(decision ->
                decision.questionId().equals(answer.questionId())
                        && decision.scope() != Scope.UNRESOLVED))
                .forEach(answer -> ambiguityEvidence.add(new ConversationMessage(
                        "user", confirmedAnswerEvidence(answer))));
        List<String> ambiguities = ambiguityDetector.detect(request.rawPrompt(), context, ambiguityEvidence)
                .stream().filter(value -> !decisions.coversUnknown(value)).toList();
        // 首次卡片保留，二次检索只补充新证据；旧卡片和新证据都要经过用途及安全校验。
        PlanningFactMerger.MergeResult mergedFacts = planConfirmed
                ? new PlanningFactMerger().merge(
                        filterBoundPlanningFacts(confirmedPlan.planningContextDigest() == null ? List.of()
                                : confirmedPlan.planningContextDigest().factCards(), request.permissionPolicy()),
                        context, contextQuery)
                : new PlanningFactMerger.MergeResult(List.of(), 0);
        var planningFacts = mergedFacts.facts();
        List<String> contextConflicts = contextConflictDetector.detect(context, planAnswers,
                contextQuery, mergedFacts.boundFacts());
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
        List<String> planningWarnings = new ArrayList<>(confirmedPlan.planningContextDigest() == null
                ? List.of() : confirmedPlan.planningContextDigest().warnings());
        if (mergedFacts.omittedCount() > 0) planningWarnings.add(
                "二次检索另发现 " + mergedFacts.omittedCount() + " 条相关事实，超出最终事实卡片预算，请核对上下文报告。");
        List<String> finalAmbiguities = ambiguities;
        try (LogCorrelation.Scope ignored = LogCorrelation.bindWorkflow(workflowNamespace, workflowResourceId)) {
            try {
                OptimizationResult result = enhancementProvider.enhanceValidated(
                        new EnhancementProviderRequest(
                                request.rawPrompt(),
                                context,
                                template,
                                finalAmbiguities,
                                planAnswers,
                                planConfirmed,
                                constraints,
                                conversation,
                                request.enhancement(),
                                selectedModelId,
                                planningFacts,
                                decisions.decisions()
                        ),
                        // 回调只组装及校验结果；历史写入继续由调用方在最终成功返回后执行一次。
                        providerResponse -> com.promptoptimizer.common.logging.PipelineStageTiming.measure(
                                "prompt.optimize", "result.assemble", selectedModelId, () -> resultAssembler.assemble(
                                providerResponse,
                                context,
                                template,
                                finalAmbiguities,
                                planAnswers,
                                planConfirmed,
                                constraints,
                                Boolean.TRUE.equals(request.enhancement().includeExamples()),
                                Math.max(0, clock.millis() - startedAt),
                                request.rawPrompt(),
                                planningFacts,
                                planningWarnings
                        ).withModelVersion(selectedModel != null && selectedModelId.equals(providerResponse.model())
                                ? selectedModel.displayName() : "")));
                long latencyMs = result.latencyMs();
                if (result.provider().mock()) {
                    ModelCallLogger.completed("prompt.optimize", result.provider().provider(),
                            result.provider().model(), "MOCK_PROVIDER", true, 1, 1, latencyMs, null);
                }
                LOGGER.info("event=optimization.completed requestId={} workflowId={} mock={} "
                                + "sections={} ambiguities={} durationMs={}",
                        LogFields.value(MDC.get("requestId")),
                        LogFields.value(MDC.get("workflowId")),
                        result.provider().mock(),
                        result.sections().size(),
                        result.ambiguities().size(),
                        latencyMs);
                return result;
            } catch (RuntimeException exception) {
                LOGGER.error("event=optimization.failed requestId={} workflowId={} failureType={} validationReason={} validationField={} durationMs={}",
                        LogFields.value(MDC.get("requestId")),
                        LogFields.value(MDC.get("workflowId")),
                        failureType(exception),
                        exception instanceof ProviderResponseValidationException validation
                                ? validation.getReason().name() : "-",
                        exception instanceof ProviderResponseValidationException validation
                                ? validation.getField() : "-",
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

    /** 再次增强可能收紧受保护路径；旧 Plan 证据也须服从本次权限边界。 */
    private List<PlanningFactCard> filterBoundPlanningFacts(List<PlanningFactCard> facts,
                                                           PermissionPolicyInput policy) {
        if (facts.isEmpty()) return facts;
        ContextAnalysisRequest evidence = new ContextAnalysisRequest("", facts.stream()
                .map(card -> new ContextFileInput(card.sourcePath(), card.evidence(), "text")).toList());
        var allowed = protectedContextFilter.filter(evidence, policy).request().files().stream()
                .map(ContextFileInput::path).collect(java.util.stream.Collectors.toSet());
        return facts.stream().filter(card -> allowed.contains(card.sourcePath())).toList();
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
