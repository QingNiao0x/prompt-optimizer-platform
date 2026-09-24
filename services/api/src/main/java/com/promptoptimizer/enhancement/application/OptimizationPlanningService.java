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
        List<PlanQuestion> requiredConflicts = conflictQuestions(planningContext.digest());
        List<PlanQuestion> modelQuestions = questionFilter.filter(validated.questions(), providerRequest).stream()
                .filter(question -> requiredConflicts.stream()
                        .noneMatch(conflict -> sameConflictDimension(question.question(), conflict.question())))
                .toList();
        List<PlanQuestion> candidates = new ArrayList<>(requiredConflicts);
        candidates.addAll(modelQuestions);
        List<PlanQuestion> questions = candidates.stream().limit(MAX_QUESTIONS).toList();
        metrics.generated(validated.questions().size() + requiredConflicts.size(), questions.size());
        String summary = questions.isEmpty()
                ? "当前需求及已提供材料足以进入最终增强，无需额外确认。"
                : validated.summary();
        // 当前需求决定任务类型；附件只提供事实，不能把代码任务误判为附件的研究主题。
        var inferredTemplate = templateRegistry.infer(request.rawPrompt());
        if (inferredTemplate == com.promptoptimizer.enhancement.domain.TemplateCode.GENERAL) {
            inferredTemplate = templateRegistry.infer(request.contextDescription());
        }
        PlanningSessionService.PlanRegistration registration = planningSessionService.registerPlan(
                request.rawPrompt(),
                request.contextDescription(),
                request.conversationHistory(),
                planningContext,
                questions
        );
        return new OptimizationPlan(
                summary,
                questions,
                inferredTemplate,
                new ProviderMetadata(validated.provider(), validated.model(), validated.mock()),
                Math.max(0, clock.millis() - startedAt),
                registration.planId(),
                registration.planningContext(),
                registration.expiresAt()
        );
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
            conflicts.add(new PlanQuestion(
                    "context-conflict-" + (++sequence),
                    question,
                    "上传材料对同一项目事实给出了不同内容。请说明本次以哪份资料或规则为准。",
                    PlanQuestionType.FREE_TEXT,
                    List.of(),
                    List.of(),
                    true
            ));
            if (conflicts.size() == 3) break;
        }
        return List.copyOf(conflicts);
    }

    /** 冲突已有服务端必问项时，不再保留同一字段的模型改写问题。 */
    private boolean sameConflictDimension(String question, String conflict) {
        var field = Pattern.compile("资料对“([^”]{2,40})”").matcher(conflict);
        if (!field.find()) return false;
        String key = field.group(1);
        if (question.contains(key)) return true;
        if (key.contains("阈值")) return question.contains("阈值") || question.contains("金额") && question.contains("审批");
        if (key.contains("范围")) return question.contains("范围") || question.contains("地区") || question.contains("区域");
        if (key.contains("时限")) return question.contains("时限") || question.contains("期限") || question.contains("时间");
        if (key.contains("口径")) return question.contains("口径") || question.contains("定义") || question.contains("统计标准");
        if (key.contains("标准")) return question.contains("标准") || question.contains("验收");
        if (key.contains("规则")) return question.contains("规则") || question.contains("如何处理");
        if (key.contains("格式")) return question.contains("格式") || question.contains("类型");
        return key.contains("版本") && (question.contains("版本") || question.contains("采用哪份"));
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
