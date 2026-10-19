package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.common.logging.LogCorrelation;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.service.ContextAnalyzer;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.*;
import com.promptoptimizer.enhancement.service.PlanningSessionService;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.policy.domain.PlatformConstraintRules;
import com.promptoptimizer.policy.service.ProtectedContextFilter;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.NaturalPromptProvider;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 自然增强及其 Plan 的应用入口。只复用身份、资料安全和会话校验，不使用旧任务模板、
 * 业务约束补全、段落生成或组装器；原始题答完整进入新的生成协议。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class NaturalEnhancementService {
    private final ContextAnalyzer analyzer;
    private final ProtectedContextFilter filter;
    private final PlanningSessionService sessions;
    private final CurrentActor actor;
    private final PlatformModelCatalog catalog;
    private final NaturalPromptProvider provider;
    private final SensitiveValueDetector sensitive = new SensitiveValueDetector();
    private final Clock clock;

    /** 复用基础安全与会话服务，独立Provider负责新生成和新提问协议。 */
    @Autowired
    public NaturalEnhancementService(ContextAnalyzer analyzer, ProtectedContextFilter filter,
            PlanningSessionService sessions, CurrentActor actor, PlatformModelCatalog catalog, NaturalPromptProvider provider) {
        this(analyzer, filter, sessions, actor, catalog, provider, Clock.systemUTC());
    }

    /** 受控测试可注入时钟，生产使用服务端UTC时间，不信任客户端时间与所有者。 */
    public NaturalEnhancementService(ContextAnalyzer analyzer, ProtectedContextFilter filter,
            PlanningSessionService sessions, CurrentActor actor, PlatformModelCatalog catalog,
            NaturalPromptProvider provider, Clock clock) {
        this.analyzer = analyzer;
        this.filter = filter;
        this.sessions = sessions;
        this.actor = actor;
        this.catalog = catalog;
        this.provider = provider;
        this.clock = clock;
    }

    /** 生成完整自然正文；零问题 Plan 与有回答 Plan 均通过同一服务端绑定入口。 */
    public PromptDocument optimize(OptimizationRequest request) {
        return optimize(request, NaturalPromptInstructions.VERSION);
    }

    /** 旧历史显式选择已知版本；新请求使用当前版本，未知版本在模型调用前拒绝。 */
    public PromptDocument optimize(OptimizationRequest request, String strategyVersion) {
        if (!NaturalPromptInstructions.supports(strategyVersion)) {
            throw new com.promptoptimizer.common.exception.UnsupportedResultVersionException("自然增强版本暂不支持，请恢复原始需求重新增强。");
        }
        long start = clock.millis();
        actor.require();
        rejectInput(request.rawPrompt());
        rejectInput(request.context().customDescription());
        request.conversationHistory().forEach(message -> rejectInput(message.content()));
        var confirmed = sessions.confirm(request.rawPrompt(), request.context().customDescription(),
                request.conversationHistory(), request.planConfirmation());
        confirmed.answers().forEach(answer -> rejectInput(answer.answer()));
        if (confirmed.bound() && request.modelId() != null && !request.modelId().isBlank()
                && !Objects.equals(request.modelId().trim(), confirmed.modelId())) {
            throw new InvalidOptimizationRequestException("所选模型与已确认的计划不一致，请重新生成确认问题。");
        }
        var model = catalog.resolve(confirmed.bound() ? confirmed.modelId() : request.modelId());
        var filtered = filter.filter(request.context(), request.permissionPolicy());
        String query = request.rawPrompt() + confirmed.answers().stream()
                .map(answer -> "\n" + answer.question() + "：" + answer.answer()).collect(Collectors.joining());
        ContextSnapshot context = sessions.reusableContext(confirmed, filtered.request(), query)
                .orElseGet(() -> filter.attachReport(analyzer.analyze(filtered.request(), query), filtered));
        // 再次检索不能覆盖首次资料。此前快照仍按当前权限重新过滤，冲突由模型保持来源而非选定一方。
        ContextSnapshot planningContext = confirmed.planningContext() == null ? null : permittedSnapshot(
                sessions.resolveForPlan(confirmed.planningContext(), request.rawPrompt(),
                        request.context().customDescription()).snapshot(), request.permissionPolicy());
        List<ConversationMessage> conversation = Boolean.TRUE.equals(request.enhancement().includeConversationHistory())
                ? request.conversationHistory() : List.of();
        // 自然模式不自动追加平台固定条款；仅组装用户明确设置的权限边界。
        // 原始需求和正文中的具体要求仍完整保留，敏感扫描与资料过滤独立执行。
        List<String> permissions = additionalPermissions(request.permissionPolicy());
        var groups = new ConstraintBundle(List.of(), List.of(), permissions);
        var input = new NaturalPromptInput(request.rawPrompt(), context, confirmed.answers(), conversation,
                Boolean.TRUE.equals(request.enhancement().includeExamples()), groups.visibleConstraints(),
                model.publicId(), planningContext).withStrategyVersion(strategyVersion);
        String workflowId = request.planConfirmation() == null ? null : request.planConfirmation().planId();
        try (var scope = LogCorrelation.bindWorkflow("planning-plan", workflowId)) {
            return provider.enhance(input, draft -> {
                NaturalTaskUnderstanding task = null;
                if (NaturalPromptInstructions.requiresTaskUnderstanding(strategyVersion)) {
                    if (draft == null || draft.taskUnderstanding() == null) throw invalid("自然链路缺少任务理解记录");
                    task = draft.taskUnderstanding().resolve(input);
                }
                String body = validateBody(draft, groups.visibleConstraints(), strategyVersion);
                if (NaturalPromptInstructions.requiresTaskReview(strategyVersion)) {
                    // 只核对真实模型正文与最终组装正文的引用，不用追加原文掩盖模型漏写。
                    task.review().validateEnhancement(task, draft.optimizedPrompt().strip());
                    task.review().validateEnhancement(task, body);
                }
                List<String> warnings = new ArrayList<>(context.warnings());
                if (planningContext != null) warnings.addAll(planningContext.warnings());
                if (draft.provider().mock()) warnings.add("当前为离线Mock示例，不代表真实模型增强质量。");
                return new PromptDocument(body, PromptDocument.ResultKind.TEXT, null, context, List.of(),
                        groups.visibleConstraints(), TemplateCode.GENERAL,
                        new ProviderMetadata(draft.provider().provider(), draft.provider().model(), draft.provider().mock(),
                                draft.provider().mock() ? "" : model.displayName()),
                        Math.max(0, clock.millis() - start), warnings.stream().distinct().toList(), List.of(),
                        PromptFormat.COMPACT, groups, PromptDocument.NATURAL_PIPELINE, strategyVersion, draft.framework().name(), null, 0,
                        confirmed.bound() ? confirmed.answers() : null);
            });
        }
    }

    /** 新提问策略完整保留重要未知，逐题展示；注册问题后最终回答仍受服务端所有权和TTL约束。 */
    public OptimizationPlan plan(OptimizationPlanRequest request) {
        return plan(request, NaturalPromptInstructions.VERSION);
    }

    /** 服务端兼容分发与受控对照可显式选择已知策略，浏览器请求不能自报版本。 */
    public OptimizationPlan plan(OptimizationPlanRequest request, String strategyVersion) {
        if (!NaturalPromptInstructions.supports(strategyVersion)) {
            throw new com.promptoptimizer.common.exception.UnsupportedResultVersionException("自然Plan版本暂不支持，请重新生成确认问题。");
        }
        long start = clock.millis();
        actor.require();
        rejectInput(request.rawPrompt());
        rejectInput(request.contextDescription());
        request.conversationHistory().forEach(message -> rejectInput(message.content()));
        var model = catalog.resolve(request.modelId());
        var resolved = sessions.resolveForPlan(request.planningContext(), request.rawPrompt(), request.contextDescription());
        ContextSnapshot context = resolved.snapshot() == null
                ? analyzer.analyze(new ContextAnalysisRequest(request.contextDescription(), List.of()), request.rawPrompt())
                : resolved.snapshot();
        var input = new NaturalPromptInput(request.rawPrompt(), context, List.of(), request.conversationHistory(),
                false, List.of(), model.publicId()).withStrategyVersion(strategyVersion);
        PlanningProviderResponse response = provider.plan(input, candidate -> {
            validateQuestions(candidate);
            return NaturalPromptInstructions.requiresTaskUnderstanding(strategyVersion)
                    ? validatePlanningUnderstanding(candidate, input) : candidate;
        });
        // 注册会话是副作用，必须位于全部模型校验及修复完成后，失败尝试不保存伪计划。
        var registration = sessions.registerPlan(request.rawPrompt(), request.contextDescription(),
                request.conversationHistory(), resolved, response.questions(), model.publicId());
        return new OptimizationPlan(response.summary(), response.questions(), TemplateCode.AUTO,
                new ProviderMetadata(response.provider(), response.model(), response.mock(), response.mock() ? "" : model.displayName()),
                Math.max(0, clock.millis() - start), registration.planId(), registration.planningContext(), registration.expiresAt());
    }

    /** 只补明确设置的权限原句，不追加平台固定条款，不删除模型或用户的重要条件。 */
    private String validateBody(NaturalPromptDraft draft, List<String> required, String strategyVersion) {
        if (draft == null || draft.framework() == null || draft.provider() == null
                || draft.optimizedPrompt() == null || draft.optimizedPrompt().isBlank()) throw invalid("自然正文不完整");
        String body = draft.optimizedPrompt().strip();
        if (sensitive.containsCredential(body)) throw invalid("自然正文包含疑似敏感凭据");
        if (NaturalPromptInstructions.requiresTaskUnderstanding(strategyVersion)) return assembleBoundaries(body, required);
        for (String rule : required) {
            int first = body.indexOf(rule);
            if (first >= 0 && body.indexOf(rule, first + rule.length()) >= 0) throw invalid("平台边界重复");
        }
        List<String> missing = required.stream().filter(rule -> !body.contains(rule)).toList();
        return body + (missing.isEmpty() ? "" : "\n\n" + String.join(" ", missing));
    }

    /**
     * 第二版起统一组装用户追加权限，仅归并独占一行的完整权限原句；不对自由正文做近义删除。
     * 代码围栏、引用、条件句和附带业务内容保留原样，不能为消除重复而丢失独立条件。
     */
    private String assembleBoundaries(String body, List<String> required) {
        if (required.isEmpty()) return body;
        Set<String> owned = new HashSet<>();
        List<String> lines = new ArrayList<>();
        String fence = null;
        for (String line : body.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String marker = trimmed.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
                lines.add(line);
                continue;
            }
            // 缩进代码同样不参与平台句归并；其它自由句即使含原句也不据此判定等价覆盖。
            boolean standalone = fence == null && !line.startsWith("    ") && !line.startsWith("\t") && required.contains(trimmed);
            if (!standalone || owned.add(trimmed)) lines.add(line);
        }
        String content = String.join("\n", lines).strip();
        List<String> missing = required.stream().distinct().filter(rule -> !owned.contains(rule)).toList();
        // 两次真实候选未证明附加范围说明能改善短文案交付，撤回该说明，不叠加新的通用指令。
        return content + (missing.isEmpty() ? "" : "\n\n" + String.join("\n", missing));
    }

    /** 校验协议容量和引用完整性；不截取问题、不用推荐项填答，也不凭题号证明语义等价。 */
    private void validateQuestions(PlanningProviderResponse response) {
        if (response == null || response.summary() == null || response.summary().isBlank()
                || response.questions() == null || response.questions().size() > 64) throw invalid("Plan响应不完整或超出容量");
        if (sensitive.containsCredential(response.summary())) throw invalid("Plan包含疑似凭据");
        Set<String> ids = new HashSet<>();
        for (PlanQuestion question : response.questions()) {
            if (question == null || !validId(question.id()) || !ids.add(question.id()) || question.type() == null
                    || !validText(question.question(), 300) || !safeOptionalText(question.hint(), 1500)
                    || question.options().size() > 12 || question.examples().size() > 8) throw invalid("Plan问题协议无效");
            if (question.type() != PlanQuestionType.FREE_TEXT && question.options().size() < 2) throw invalid("Plan选项不完整");
            Set<String> optionIds = new HashSet<>();
            for (PlanOption option : question.options()) {
                if (option == null || !validId(option.id()) || !optionIds.add(option.id())
                        || !validText(option.label(), 300) || !validText(option.answer(), 1500)
                        || !safeOptionalText(option.description(), 1500) || !safeText(option.recommendationReason(), 1500)
                        || (option.recommended() && option.recommendationReason().isBlank())) throw invalid("Plan选项协议无效");
            }
            if (question.examples().stream().anyMatch(value -> !safeText(value, 1500))) throw invalid("Plan示例协议无效");
        }
    }

    /**
     * Plan与正文共用任务理解和来源校验；题号仅验证问题与重要未知的关联，不证明提问必要性。
     * 不根据职业、词表或模型自报置信度删除问题，真实题答和原始要求仍完整进入最终生成。
     */
    private PlanningProviderResponse validatePlanningUnderstanding(PlanningProviderResponse response, NaturalPromptInput input) {
        if (response.taskUnderstanding() == null) throw invalid("Plan缺少任务理解记录");
        var task = response.taskUnderstanding().resolve(input);
        Set<String> questions = response.questions().stream().map(PlanQuestion::id).collect(Collectors.toSet());
        if (NaturalPromptInstructions.requiresTaskReview(input.strategyVersion())) task.review().validatePlan(questions);
        Set<String> linked = new HashSet<>();
        for (var decision : task.openDecisions()) {
            if (decision.questionId() == null || decision.questionId().isBlank()) continue;
            if (!questions.contains(decision.questionId())) throw invalid("重要未知引用了不存在的Plan题号");
            linked.add(decision.questionId());
        }
        if (!linked.equals(questions)) throw invalid("Plan问题缺少对应的交付影响说明");
        // 推荐只是一种UI建议。用户保留信息不能靠“常见值”填补；不删题、选项或答案，也不代用户作答。
        // 同一问题关联多个未知时，只要其中包含用户信息就保守取消推荐；未分类记录同样保守处理。
        Set<String> userInformation = task.openDecisions().stream()
                .filter(decision -> decision.kind() == NaturalTaskUnderstanding.DecisionKind.USER_INFORMATION)
                .map(NaturalTaskUnderstanding.OpenDecision::questionId).collect(Collectors.toSet());
        List<PlanQuestion> normalized = response.questions().stream().map(question -> {
            if (!userInformation.contains(question.id())) return question;
            List<PlanOption> options = question.options().stream().map(option -> new PlanOption(option.id(), option.label(),
                    option.description(), option.answer(), false, "")).toList();
            return new PlanQuestion(question.id(), question.question(), question.hint(), question.type(), options,
                    question.examples(), question.allowCustomAnswer());
        }).toList();
        return new PlanningProviderResponse(response.summary(), normalized, response.provider(), response.model(), response.mock(), task);
    }

    /** 旧快照内容只在当前权限仍允许时传给模型，不能用历史资料绕过本次路径排除。 */
    private ContextSnapshot permittedSnapshot(ContextSnapshot snapshot, PermissionPolicyInput policy) {
        if (snapshot == null) return null;
        var files = snapshot.fileSnippets().stream()
                .map(file -> new ContextFileInput(file.path(), file.content(), file.language())).toList();
        var allowed = filter.filter(new ContextAnalysisRequest("", files), policy).request().files().stream()
                .map(ContextFileInput::path).collect(Collectors.toSet());
        var snippets = snapshot.fileSnippets().stream().filter(file -> allowed.contains(file.path())).toList();
        if (snippets.size() == snapshot.fileSnippets().size()) return snapshot;
        // 聚合技术栈、目录等没有逐字段来源，不能证明未引用新排除资料；仅保留仍允许的原片段与用户描述。
        // 不重新调用摘要模型，以免权限收紧导致额外推理或产生第二套资料事实。
        return new ContextSnapshot(snapshot.customDescription(), List.of(), List.of(), List.of(), snippets,
                "PARTIAL", List.of(), List.of("此前资料已按当前权限筛选，技术栈和目录等聚合摘要未复用。"),
                List.of(), snapshot.analysisVersion());
    }

    /** 将用户明确设置的路径和操作转成权限原句，不注入默认平台条款。 */
    private List<String> additionalPermissions(PermissionPolicyInput policy) {
        List<String> rules = new ArrayList<>();
        policy.protectedPaths().forEach(this::rejectInput);
        policy.requireConfirmationFor().forEach(this::rejectInput);
        if (!policy.protectedPaths().isEmpty()) rules.add(PlatformConstraintRules.PATH_PREFIX + String.join("、", policy.protectedPaths()) + "。");
        if (!policy.requireConfirmationFor().isEmpty()) rules.add(PlatformConstraintRules.ACTION_PREFIX + String.join("、", policy.requireConfirmationFor()) + "。");
        return List.copyOf(rules);
    }

    private void rejectInput(String text) {
        if (sensitive.containsCredential(text)) throw new InvalidOptimizationRequestException("输入包含疑似敏感凭据，请脱敏后重试。");
    }
    private boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    private boolean validText(String value, int maximum) { return value != null && !value.isBlank() && safeText(value, maximum); }
    private boolean safeText(String value, int maximum) { return value != null && value.length() <= maximum && !sensitive.containsCredential(value); }
    /** 提示说明是可选协议字段，缺省不拒绝合法问题；存在时仍校验容量和凭据。 */
    private boolean safeOptionalText(String value, int maximum) { return value == null || safeText(value, maximum); }
    private ProviderException invalid(String message) { return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false); }
}
