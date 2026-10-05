package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.service.OptimizationPlanningService;
import com.promptoptimizer.enhancement.service.PlanQualityMetrics;
import com.promptoptimizer.enhancement.service.PlanningSessionService;
import com.promptoptimizer.common.logging.LogCorrelation;
import com.promptoptimizer.common.logging.LogFields;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.service.PromptPlanningProvider;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import com.promptoptimizer.template.service.PromptTemplateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 编排一键增强前的业务问题识别，并隔离 Provider 输出校验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class OptimizationPlanningServiceImpl implements OptimizationPlanningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OptimizationPlanningServiceImpl.class);

    private static final int MAX_QUESTIONS = 8;
    private static final int MAX_OPTIONS = 5;
    private static final int MAX_EXAMPLES = 4;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final List<String> INTERNAL_TERMS = List.of(
            "templatecode",
            "feature_development",
            "bug_fix",
            "research_analysis",
            "选择任务模板",
            "确认缺失维度",
            "缺失维度"
    );

    private final PromptPlanningProvider planningProvider;
    private final PromptTemplateRegistry templateRegistry;
    private final PlanningSessionService planningSessionService;
    private final SensitiveValueDetector sensitiveValueDetector;
    private final Clock clock;
    private final PlanQuestionFilter questionFilter = new PlanQuestionFilter();
    private PlanQualityMetrics metrics = new PlanQualityMetricsImpl(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private PlatformModelCatalog modelCatalog;

    /** Spring 运行时注入平台模型目录；直接构造的既有单元测试继续使用 Mock 默认模型。 */
    @Autowired(required = false)
    public void setModelCatalog(PlatformModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    @Autowired
    public void setMetrics(PlanQualityMetrics metrics) { this.metrics = metrics; }

    @Autowired
    public OptimizationPlanningServiceImpl(
            PromptPlanningProvider planningProvider,
            PromptTemplateRegistry templateRegistry,
            PlanningSessionService planningSessionService
    ) {
        this(planningProvider, templateRegistry, planningSessionService, Clock.systemUTC());
    }

    OptimizationPlanningServiceImpl(
            PromptPlanningProvider planningProvider,
            PromptTemplateRegistry templateRegistry,
            PlanningSessionService planningSessionService,
            Clock clock
    ) {
        this.planningProvider = planningProvider;
        this.templateRegistry = templateRegistry;
        this.planningSessionService = planningSessionService;
        this.sensitiveValueDetector = new SensitiveValueDetector();
        this.clock = clock;
    }

    /**
     * 结合已分析的安全上下文生成自然语言确认问题，保留缺少事实的自由填写问题。
     */
    public OptimizationPlan plan(OptimizationPlanRequest request) {
        long startedAt = clock.millis();
        String contextId = request.planningContext() == null ? null : request.planningContext().contextId();
        String workflowNamespace = contextId == null ? "planning-plan" : "planning-context";
        try (LogCorrelation.Scope ignored = LogCorrelation.bindWorkflow(workflowNamespace, contextId)) {
            try {
                rejectCredentials(request.rawPrompt());
                rejectCredentials(request.contextDescription());
                request.conversationHistory().forEach(message -> rejectCredentials(message.content()));
                PlanningSessionService.ResolvedPlanningContext planningContext = planningSessionService.resolveForPlan(
                        request.planningContext(),
                        request.rawPrompt(),
                        request.contextDescription()
                );
                var selectedModel = modelCatalog == null ? null : modelCatalog.resolve(request.modelId());
                String selectedModelId = selectedModel == null
                        ? legacyModelId(request.modelId())
                        : selectedModel.publicId();
                PlanningProviderRequest providerRequest = PlanningDecisionPolicy.enrich(new PlanningProviderRequest(
                        request.rawPrompt().trim(),
                        request.contextDescription().trim(),
                        request.conversationHistory(),
                        planningContext.digest(),
                        selectedModelId
                ));
                PlanningProviderResponse validated = com.promptoptimizer.common.logging.PipelineStageTiming.measure(
                        "plan.generate", "provider.total", selectedModelId, () -> requestValidatedPlan(providerRequest));
                List<PlanQuestion> requiredConflicts = conflictQuestions(planningContext.digest());
                List<PlanQuestion> modelQuestions = questionFilter.filter(validated.questions(), providerRequest).stream()
                        .filter(question -> requiredConflicts.stream()
                                .filter(conflict -> sameConflictDimension(question, conflict.question())).count() != 1)
                        .toList();
                List<PlanQuestion> candidates = new ArrayList<>(requiredConflicts);
                candidates.addAll(modelQuestions);
                List<PlanQuestion> questions = candidates.stream()
                        .limit(MAX_QUESTIONS)
                        .map(PlanChoiceCompleter::complete)
                        .map(question -> PlanRecommendationAligner.align(question, providerRequest))
                        .map(this::validateRecommendationCount)
                        .toList();
                metrics.generated(validated.questions().size() + requiredConflicts.size(), questions.size());
                String summary = questions.isEmpty()
                        ? "当前需求及已提供材料足以进入最终增强，无需额外确认。"
                        : validated.summary();
                // 当前需求决定任务类型；附件只提供事实，不能把代码任务误判为附件的研究主题。
                var inferredTemplate = templateRegistry.infer(request.rawPrompt());
                // GENERAL 也可能是明确的写作目标；背景材料不能替本次目标重新分类。
                PlanningSessionService.PlanRegistration registration = planningSessionService.registerPlan(
                        request.rawPrompt(),
                        request.contextDescription(),
                        request.conversationHistory(),
                        planningContext,
                        questions,
                        selectedModelId
                );
                long latencyMs = Math.max(0, clock.millis() - startedAt);
                if (validated.mock()) {
                    ModelCallLogger.completed("plan.generate", validated.provider(), validated.model(),
                            "MOCK_PROVIDER", true, 1, 1, latencyMs, null);
                }
                LOGGER.info("event=plan.completed requestId={} workflowId={} questions={} template={} mock={} durationMs={}",
                        LogFields.value(MDC.get("requestId")),
                        LogFields.value(MDC.get("workflowId")),
                        questions.size(),
                        inferredTemplate.name(),
                        validated.mock(),
                        latencyMs);
                return new OptimizationPlan(
                        summary,
                        questions,
                        inferredTemplate,
                        new ProviderMetadata(validated.provider(), validated.model(), validated.mock(),
                                selectedModel != null && selectedModelId.equals(validated.model())
                                        ? selectedModel.displayName() : ""),
                        latencyMs,
                        registration.planId(),
                        registration.planningContext(),
                        registration.expiresAt()
                );
            } catch (RuntimeException exception) {
                LOGGER.error("event=plan.failed requestId={} workflowId={} failureType={} validationReason={} validationField={} durationMs={}",
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

    private String legacyModelId(String requestedModelId) {
        if (requestedModelId != null && !requestedModelId.isBlank()) {
            throw new InvalidOptimizationRequestException("平台模型目录不可用，请稍后重试。");
        }
        return null;
    }

    /** 记录稳定的 Provider 故障分类或异常类型，不读取异常消息及上游响应正文。 */
    private String failureType(RuntimeException exception) {
        if (exception instanceof com.promptoptimizer.provider.domain.ProviderException providerException) {
            return providerException.getFailureType().name();
        }
        return LogFields.value(exception.getClass().getSimpleName());
    }

    /** 将服务端已核实的材料冲突变成必问项，避免计划 Provider 漏掉跨文件口径冲突。 */
    private List<PlanQuestion> conflictQuestions(com.promptoptimizer.enhancement.domain.PlanningContextDigest digest) {
        if (digest == null) return List.of();
        List<PlanQuestion> conflicts = new ArrayList<>();
        int sequence = 0;
        for (String warning : digest.warnings()) {
            if (!warning.startsWith("资料对“") || !warning.contains("请确认本次采用哪一项")) continue;
            String question = warning.length() <= 300
                    ? warning
                    : warning.substring(0, 280).stripTrailing() + "……请确认本次采用哪一项。";
            conflicts.add(conflictChoice("context-conflict-" + (++sequence), question, warning));
            if (conflicts.size() == 3) break;
        }
        return List.copyOf(conflicts);
    }

    /**
     * 冲突题给出两份取值、保留冲突和按原始需求重写四条具体路径。
     * 建议是保留冲突并标明，不替用户选定其中一份。
     */
    private PlanQuestion conflictChoice(String id, String question, String warning) {
        String first = "第一份资料中的取值";
        String second = "第二份资料中的取值";
        var values = Pattern.compile("（([^）]{1,80})）").matcher(warning);
        if (values.find()) {
            first = values.group(1).trim();
        }
        if (values.find()) {
            second = values.group(1).trim();
        }
        return new PlanQuestion(
                id,
                question,
                "上传材料对同一事实给出了不同内容。请选定本次采用的口径。",
                PlanQuestionType.SINGLE_CHOICE,
                List.of(
                        new com.promptoptimizer.enhancement.domain.PlanOption(
                                id + "-first", "采用第一份", first, "本次采用：" + first, false),
                        new com.promptoptimizer.enhancement.domain.PlanOption(
                                id + "-second", "采用第二份", second, "本次采用：" + second, false),
                        new com.promptoptimizer.enhancement.domain.PlanOption(
                                id + "-both", "两份都保留并标明冲突", "结果中同时写出两个取值和来源",
                                "两份资料都保留，并在结果中标明冲突和各自来源，不悄悄选定其中一个。", true),
                        new com.promptoptimizer.enhancement.domain.PlanOption(
                                id + "-rewrite", "按原始需求重新写明", "不以这两份数字为准",
                                "本次不采用这两份资料中的取值，按原始需求重新写明该事实。", false)
                ),
                List.of(),
                true
        );
    }

    /** 冲突已有服务端必问项时，不再保留同一字段的模型改写问题。 */
    private boolean sameConflictDimension(PlanQuestion question, String conflict) {
        var identity = PlanningConflictIdentity.parse(conflict);
        if (identity.isPresent() && identity.get().matchesQuestion(question)) return true;
        var field = Pattern.compile("资料对“([^”]{2,40})”").matcher(conflict);
        if (!field.find()) return false;
        return PlanDecisionIdentity.repeatsConflict(question.question(), field.group(1));
    }

    /** 将应用层校验交给 Provider 的同一个重试预算，避免格式重试与业务校验重试相乘。 */
    private PlanningProviderResponse requestValidatedPlan(PlanningProviderRequest request) {
        AtomicBoolean alreadyValidated = new AtomicBoolean();
        return planningProvider.planValidated(request, response -> {
            // 只有实际进入下一次业务校验时才计重试，末次失败不能再记一次未发生的请求。
            if (alreadyValidated.getAndSet(true)) metrics.retry();
            PlanningProviderResponse validated = validate(response);
            var guard = new RequirementFidelityGuard();
            var decisionPolicy = PlanningDecisionPolicy.from(request);
            List<String> rules = guard.explicitRules(request.rawPrompt(), List.of());
            var ruleValidation = guard.prepare(rules);
            // 包括非推荐选项；不能让用户通过候选答案无意放弃原始需求中的明确规则。
            // 格式和安全仍校验所有项；已被明确事实消除的问题不因无关候选触发额外模型重试。
            questionFilter.filter(validated.questions(), request).forEach(question -> {
                decisionPolicy.validateCandidate(question.hint(), question.question(), "questions.hint");
                question.examples().forEach(example -> {
                    decisionPolicy.validateCandidate(example, question.question(), "questions.examples");
                    ruleValidation.validateProposal(example, "questions.examples");
                });
                question.options().forEach(option -> {
                    decisionPolicy.validateCandidate(option.answer(), question.question(), "questions.options.answer");
                    decisionPolicy.validateCandidate(option.label(), question.question(), "questions.options.label");
                    decisionPolicy.validateCandidate(option.description(), question.question(), "questions.options.description");
                    decisionPolicy.validateCandidate(option.recommendationReason(), question.question(), "questions.options.recommendationReason");
                    ruleValidation.validateProposal(option.answer(), "questions.options.answer");
                    ruleValidation.validateProposal(option.label(), "questions.options.label");
                    ruleValidation.validateProposal(option.description(), "questions.options.description");
                    ruleValidation.validate(option.recommendationReason(), "questions.options.recommendationReason");
                });
            });
            return validated;
        });
    }

    /**
     * 拒绝不可展示、过量或暴露内部实现词汇的 Provider 结果。
     */
    private PlanningProviderResponse validate(PlanningProviderResponse response) {
        if (response == null || isBlank(response.summary()) || response.summary().length() > 500
                || containsInternalTerm(response.summary())
                || isBlank(response.provider()) || isBlank(response.model())
                || response.questions() == null || response.questions().size() > MAX_QUESTIONS) {
            throw invalidResponse(Reason.PLAN_STRUCTURE_INVALID, "plan");
        }

        Set<String> questionIds = new HashSet<>();
        rejectProviderCredential(response.summary(), "summary");
        List<PlanQuestion> questions = new ArrayList<>();
        for (PlanQuestion question : response.questions()) {
            validateQuestionEnvelope(question, questionIds);
            rejectProviderCredential(question.question(), "questions.question");
            rejectProviderCredential(question.hint(), "questions.hint");
            questions.add(normalizeQuestion(question));
        }
        return new PlanningProviderResponse(
                response.summary().trim(),
                questions,
                response.provider().trim(),
                response.model().trim(),
                response.mock()
        );
    }

    /** 只记录固定字段位置，帮助有限修复定位；不回显问题 ID、拒绝文本或上传内容。 */
    private void validateQuestionEnvelope(PlanQuestion question, Set<String> questionIds) {
        if (question == null) throw invalidResponse(Reason.PLAN_QUESTION_INVALID, "questions");
        if (!SAFE_ID.matcher(value(question.id())).matches() || !questionIds.add(question.id())) {
            throw invalidResponse(Reason.PLAN_QUESTION_INVALID, "questions.id");
        }
        if (isBlank(question.question()) || question.question().length() > 300 || containsInternalTerm(question.question())) {
            throw invalidResponse(Reason.PLAN_QUESTION_INVALID, "questions.question");
        }
        if (question.type() == null) throw invalidResponse(Reason.PLAN_QUESTION_INVALID, "questions.type");
        if (value(question.hint()).length() > 500 || containsInternalTerm(value(question.hint()))) {
            throw invalidResponse(Reason.PLAN_QUESTION_INVALID, "questions.hint");
        }
        if (question.options().size() > MAX_OPTIONS) throw invalidResponse(Reason.PLAN_OPTION_INVALID, "questions.options");
        if (question.examples().size() > MAX_EXAMPLES) throw invalidResponse(Reason.PLAN_QUESTION_INVALID, "questions.examples");
    }

    /**
     * 校验模型候选项的 ID、长度与敏感内容；推荐数量在依据用户证据校准后检查。
     */
    private PlanQuestion normalizeQuestion(PlanQuestion question) {
        Set<String> optionIds = new HashSet<>();
        List<PlanOption> options = new ArrayList<>();
        for (PlanOption option : question.options()) {
            if (option == null || !SAFE_ID.matcher(value(option.id())).matches()
                    || !optionIds.add(option.id())
                    || isBlank(option.label()) || option.label().length() > 120
                    || value(option.description()).length() > 300
                    || option.recommendationReason().length() > 300
                    || isBlank(option.answer()) || option.answer().length() > 1_500
                    || containsInternalTerm(option.label())
                    || containsInternalTerm(value(option.description()))
                    || containsInternalTerm(option.answer())
                    || containsInternalTerm(option.recommendationReason())) {
                throw invalidResponse(Reason.PLAN_OPTION_INVALID, "questions.options");
            }
            rejectProviderCredential(option.label(), "questions.options.label");
            rejectProviderCredential(option.description(), "questions.options.description");
            rejectProviderCredential(option.answer(), "questions.options.answer");
            // 校准会清除无依据的推荐理由，因此必须先检查其敏感内容，不能靠清除绕过校验。
            rejectProviderCredential(option.recommendationReason(), "questions.options.recommendationReason");
            options.add(new PlanOption(
                    option.id().trim(),
                    option.label().trim(),
                    value(option.description()).trim(),
                    option.answer().trim(),
                    option.recommended(),
                    option.recommendationReason()
            ));
        }
        if ((question.type() == PlanQuestionType.FREE_TEXT
                && (!options.isEmpty() || !question.allowCustomAnswer()))
                || (question.type() != PlanQuestionType.FREE_TEXT && options.size() < 2)
                || combinedAnswerLength(question.type(), options) > 1_500) {
            throw invalidResponse(Reason.PLAN_OPTION_INVALID, "questions.options");
        }

        List<String> examples = question.examples().stream()
                .map(this::value)
                .map(String::trim)
                .filter(example -> !example.isBlank())
                .peek(example -> {
                    rejectProviderCredential(example, "questions.examples");
                    if (example.length() > 120 || containsInternalTerm(example)) {
                        throw invalidResponse(Reason.PLAN_QUESTION_INVALID, "questions.examples");
                    }
                })
                .toList();
        return new PlanQuestion(
                question.id().trim(),
                question.question().trim(),
                value(question.hint()).trim(),
                question.type(),
                options,
                examples,
                question.allowCustomAnswer()
        );
    }

    /** 推荐只是提示；先按证据校准标记，再守住最多一个推荐的展示契约，不删改可选答案。 */
    private PlanQuestion validateRecommendationCount(PlanQuestion question) {
        if (question.options().stream().filter(PlanOption::recommended).count() > 1) {
            throw invalidResponse(Reason.PLAN_RECOMMENDATION_INVALID, "questions.options.recommended");
        }
        return question;
    }

    private boolean containsInternalTerm(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        return INTERNAL_TERMS.stream().anyMatch(normalized::contains);
    }

    private int combinedAnswerLength(PlanQuestionType type, List<PlanOption> options) {
        if (type != PlanQuestionType.MULTIPLE_CHOICE || options.isEmpty()) {
            return 0;
        }
        return options.stream().mapToInt(option -> option.answer().length()).sum() + options.size() - 1;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    /** 错误只携带固定原因和字段路径，避免模型正文进入日志或修复提示。 */
    private ProviderException invalidResponse(Reason reason, String field) {
        return new ProviderResponseValidationException(reason, field);
    }

    private void rejectCredentials(String value) {
        if (sensitiveValueDetector.containsCredential(value)) {
            throw new InvalidOptimizationRequestException("输入中疑似包含真实凭据，请移除后重试。");
        }
    }

    private void rejectProviderCredential(String value, String field) {
        if (sensitiveValueDetector.containsCredential(value)) {
            throw invalidResponse(Reason.SENSITIVE_CONTENT, field);
        }
    }
}
