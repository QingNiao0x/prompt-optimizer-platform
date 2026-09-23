package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.enhancement.api.OptimizationPlanRequest;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.application.PromptPlanningProvider;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import com.promptoptimizer.template.application.PromptTemplateRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 编排一键增强前的业务问题识别，并隔离 Provider 输出校验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class OptimizationPlanningService {

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
    private PlanQualityMetrics metrics = new PlanQualityMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    @Autowired
    public void setMetrics(PlanQualityMetrics metrics) { this.metrics = metrics; }

    @Autowired
    public OptimizationPlanningService(
            PromptPlanningProvider planningProvider,
            PromptTemplateRegistry templateRegistry,
            PlanningSessionService planningSessionService
    ) {
        this(planningProvider, templateRegistry, planningSessionService, Clock.systemUTC());
    }

    OptimizationPlanningService(
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
     * 生成自然语言确认问题；计划阶段不会读取或传输项目文件正文。
     */
    public OptimizationPlan plan(OptimizationPlanRequest request) {
        long startedAt = clock.millis();
        rejectCredentials(request.rawPrompt());
        rejectCredentials(request.contextDescription());
        request.conversationHistory().forEach(message -> rejectCredentials(message.content()));
        PlanningSessionService.ResolvedPlanningContext planningContext = planningSessionService.resolveForPlan(
                request.planningContext(),
                request.rawPrompt(),
                request.contextDescription()
        );
        PlanningProviderRequest providerRequest = new PlanningProviderRequest(
                request.rawPrompt().trim(),
                request.contextDescription().trim(),
                request.conversationHistory(),
                planningContext.digest(),
                request.model()
        );
        PlanningProviderResponse validated = requestValidatedPlan(providerRequest);
        List<PlanQuestion> questions = questionFilter.filter(validated.questions(), providerRequest);
        metrics.generated(validated.questions().size(), questions.size());
        StringBuilder inferenceInput = new StringBuilder(request.rawPrompt())
                .append('\n')
                .append(request.contextDescription());
        request.conversationHistory().forEach(message -> inferenceInput
                .append('\n')
                .append(message.content()));
        if (planningContext.digest() != null) {
            planningContext.digest().technologies().forEach(value -> inferenceInput.append('\n').append(value));
            planningContext.digest().fileSummaries().forEach(value -> inferenceInput.append('\n').append(value));
        }
        PlanningSessionService.PlanRegistration registration = planningSessionService.registerPlan(
                request.rawPrompt(),
                request.contextDescription(),
                request.conversationHistory(),
                planningContext,
                questions
        );
        return new OptimizationPlan(
                validated.summary(),
                questions,
                templateRegistry.infer(inferenceInput.toString()),
                new ProviderMetadata(validated.provider(), validated.model(), validated.mock()),
                Math.max(0, clock.millis() - startedAt),
                registration.planId(),
                registration.planningContext(),
                registration.expiresAt()
        );
    }

    /** 仅对额外结构校验发现的无效模型响应再试一次，不叠加 Provider 自身重试。 */
    private PlanningProviderResponse requestValidatedPlan(PlanningProviderRequest request) {
        PlanningProviderResponse response = planningProvider.plan(request);
        try {
            return validate(response);
        } catch (ProviderException exception) {
            // 仅重试应用层额外发现的结构问题；Provider 自身已有受控重试，不叠加调用。
            if (exception.getFailureType() != ProviderFailureType.INVALID_RESPONSE) throw exception;
            metrics.retry();
            return validate(planningProvider.plan(request));
        }
    }

    /**
     * 拒绝不可展示、过量或暴露内部实现词汇的 Provider 结果。
     */
    private PlanningProviderResponse validate(PlanningProviderResponse response) {
        if (response == null || isBlank(response.summary()) || response.summary().length() > 500
                || containsInternalTerm(response.summary())
                || isBlank(response.provider()) || isBlank(response.model())
                || response.questions() == null || response.questions().size() > MAX_QUESTIONS) {
            throw invalidResponse();
        }

        Set<String> questionIds = new HashSet<>();
        rejectProviderCredential(response.summary());
        List<PlanQuestion> questions = new ArrayList<>();
        for (PlanQuestion question : response.questions()) {
            if (question == null || !SAFE_ID.matcher(value(question.id())).matches()
                    || !questionIds.add(question.id())
                    || isBlank(question.question()) || question.question().length() > 300
                    || containsInternalTerm(question.question())
                    || question.type() == null || value(question.hint()).length() > 500
                    || containsInternalTerm(value(question.hint()))
                    || question.options().size() > MAX_OPTIONS
                    || question.examples().size() > MAX_EXAMPLES) {
                throw invalidResponse();
            }
            rejectProviderCredential(question.question());
            rejectProviderCredential(question.hint());
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

    /**
     * 校验模型候选项的 ID、长度、推荐数量与敏感内容，拒绝内部术语后返回可展示问题。
     */
    private PlanQuestion normalizeQuestion(PlanQuestion question) {
        Set<String> optionIds = new HashSet<>();
        int recommended = 0;
        List<PlanOption> options = new ArrayList<>();
        for (PlanOption option : question.options()) {
            if (option == null || !SAFE_ID.matcher(value(option.id())).matches()
                    || !optionIds.add(option.id())
                    || isBlank(option.label()) || option.label().length() > 120
                    || value(option.description()).length() > 300
                    || isBlank(option.answer()) || option.answer().length() > 1_500
                    || containsInternalTerm(option.label())
                    || containsInternalTerm(value(option.description()))
                    || containsInternalTerm(option.answer())) {
                throw invalidResponse();
            }
            rejectProviderCredential(option.label());
            rejectProviderCredential(option.description());
            rejectProviderCredential(option.answer());
            recommended += option.recommended() ? 1 : 0;
            options.add(new PlanOption(
                    option.id().trim(),
                    option.label().trim(),
                    value(option.description()).trim(),
                    option.answer().trim(),
                    option.recommended()
            ));
        }
        if (recommended > 1
                || (question.type() == PlanQuestionType.FREE_TEXT
                && (!options.isEmpty() || !question.allowCustomAnswer()))
                || (question.type() != PlanQuestionType.FREE_TEXT && options.size() < 2)
                || combinedAnswerLength(question.type(), options) > 1_500) {
            throw invalidResponse();
        }

        List<String> examples = question.examples().stream()
                .map(this::value)
                .map(String::trim)
                .filter(example -> !example.isBlank())
                .peek(example -> {
                    rejectProviderCredential(example);
                    if (example.length() > 120 || containsInternalTerm(example)) {
                        throw invalidResponse();
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

    private ProviderException invalidResponse() {
        return new ProviderException(
                ProviderFailureType.INVALID_RESPONSE,
                "模型返回的确认问题格式无效",
                false
        );
    }

    private void rejectCredentials(String value) {
        if (sensitiveValueDetector.containsCredential(value)) {
            throw new InvalidOptimizationRequestException("输入中疑似包含真实凭据，请移除后重试。");
        }
    }

    private void rejectProviderCredential(String value) {
        if (sensitiveValueDetector.containsCredential(value)) {
            throw invalidResponse();
        }
    }
}
