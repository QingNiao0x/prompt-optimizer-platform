package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.context.api.ContextAnalysisRequest;
import com.promptoptimizer.context.api.ContextFileInput;
import com.promptoptimizer.context.api.PlanningContextRequest;
import com.promptoptimizer.context.application.ContextAnalyzer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.api.PlanConfirmation;
import com.promptoptimizer.enhancement.api.PlanningContextReference;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PlanningContextPreparation;
import com.promptoptimizer.identity.application.ActorIdentity;
import com.promptoptimizer.identity.application.CurrentActor;
import com.promptoptimizer.policy.application.ProtectedContextFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理“上下文准备 → 计划提问 → 最终确认”之间的短期一致性。
 *
 * <p>该模块只向计划 Provider 暴露裁剪摘要，完整的脱敏上下文快照保存在带 TTL 的服务端会话中。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class PlanningSessionService {

    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final int MAX_DIGEST_ITEM_CHARACTERS = 400;

    private final PlanningSessionStore store;
    private final ContextAnalyzer contextAnalyzer;
    private final ProtectedContextFilter protectedContextFilter;
    private final CurrentActor currentActor;
    private final SensitiveValueDetector sensitiveValueDetector;
    private final Clock clock;

    @Autowired
    public PlanningSessionService(
            PlanningSessionStore store,
            ContextAnalyzer contextAnalyzer,
            ProtectedContextFilter protectedContextFilter,
            CurrentActor currentActor
    ) {
        this(store, contextAnalyzer, protectedContextFilter, currentActor, Clock.systemUTC());
    }

    PlanningSessionService(
            PlanningSessionStore store,
            ContextAnalyzer contextAnalyzer,
            ProtectedContextFilter protectedContextFilter,
            CurrentActor currentActor,
            Clock clock
    ) {
        this.store = store;
        this.contextAnalyzer = contextAnalyzer;
        this.protectedContextFilter = protectedContextFilter;
        this.currentActor = currentActor;
        this.sensitiveValueDetector = new SensitiveValueDetector();
        this.clock = clock;
    }

    /**
     * 对初步相关文件执行安全过滤和分析，并保存可在后续请求中引用的短期快照。
     */
    public PlanningContextPreparation prepareContext(PlanningContextRequest request) {
        long startedAt = clock.millis();
        ActorIdentity actor = currentActor.require();
        rejectCredential(request.rawPrompt());
        rejectCredential(request.context().customDescription());

        ProtectedContextFilter.FilteredContext filtered = protectedContextFilter.filter(
                request.context(),
                request.permissionPolicy()
        );
        ContextSnapshot snapshot = protectedContextFilter.attachReport(
                contextAnalyzer.analyze(filtered.request(), request.rawPrompt()),
                filtered
        );
        PlanningContextReference reference = new PlanningContextReference(
                UUID.randomUUID().toString(),
                fingerprintContext(filtered.request(), request.rawPrompt())
        );
        Instant expiresAt = clock.instant().plus(SESSION_TTL);
        PlanningContextDigest digest = buildDigest(snapshot);
        store.saveContext(new PlanningSessionStore.ContextSession(
                reference,
                actor.userId(),
                fingerprintContextOwner(
                        request.rawPrompt(),
                        request.context().customDescription()
                ),
                digest,
                snapshot,
                expiresAt
        ));
        return new PlanningContextPreparation(
                reference.contextId(),
                reference.version(),
                digest,
                snapshot,
                expiresAt,
                Math.max(0, clock.millis() - startedAt)
        );
    }

    /**
     * 解析计划请求中的上下文引用，并确保它由同一份需求创建且尚未过期。
     */
    public ResolvedPlanningContext resolveForPlan(
            PlanningContextReference reference,
            String rawPrompt,
            String contextDescription
    ) {
        if (reference == null) {
            return new ResolvedPlanningContext(null, null);
        }
        PlanningSessionStore.ContextSession context = requireContext(reference);
        if (!Objects.equals(
                context.requestFingerprint(),
                fingerprintContextOwner(rawPrompt, contextDescription)
        )) {
            throw new InvalidOptimizationRequestException("需求内容已变化，请重新分析文件上下文。");
        }
        return new ResolvedPlanningContext(context.reference(), context.digest());
    }

    /**
     * 保存服务端实际展示的问题，使最终回答能够绑定同一次需求和上下文。
     */
    public PlanRegistration registerPlan(
            String rawPrompt,
            String contextDescription,
            List<ConversationMessage> conversationHistory,
            ResolvedPlanningContext planningContext,
            List<PlanQuestion> questions
    ) {
        ActorIdentity actor = currentActor.require();
        String planId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(SESSION_TTL);
        store.savePlan(new PlanningSessionStore.PlanSession(
                planId,
                actor.userId(),
                fingerprintPlanInput(rawPrompt, contextDescription, conversationHistory),
                planningContext.reference(),
                questions,
                expiresAt
        ));
        return new PlanRegistration(planId, planningContext.reference(), expiresAt);
    }

    /**
     * 校验最终回答与服务端计划一致，并使用服务端问题文案替换客户端回传文案。
     */
    public ConfirmedPlan confirm(
            String rawPrompt,
            String contextDescription,
            List<ConversationMessage> conversationHistory,
            PlanConfirmation confirmation
    ) {
        if (confirmation == null) {
            return new ConfirmedPlan(List.of(), null, false);
        }
        if (confirmation.planId() == null || confirmation.planId().isBlank()) {
            if (confirmation.planningContext() != null) {
                throw new InvalidOptimizationRequestException("计划上下文必须与有效计划编号一起提交。");
            }
            return new ConfirmedPlan(validateLegacyAnswers(confirmation.answers()), null, false);
        }

        PlanningSessionStore.PlanSession plan = requirePlan(confirmation.planId());
        if (!Objects.equals(
                plan.requestFingerprint(),
                fingerprintPlanInput(rawPrompt, contextDescription, conversationHistory)
        )) {
            throw new InvalidOptimizationRequestException("需求内容与确认问题不一致，请重新生成问题。");
        }
        if (!Objects.equals(plan.planningContext(), confirmation.planningContext())) {
            throw new InvalidOptimizationRequestException("文件上下文版本与确认问题不一致，请重新分析。");
        }

        Map<String, PlanAnswer> submitted = validateLegacyAnswers(confirmation.answers()).stream()
                .collect(Collectors.toMap(
                        PlanAnswer::questionId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Set<String> expectedIds = plan.questions().stream().map(PlanQuestion::id).collect(Collectors.toSet());
        if (!submitted.keySet().equals(expectedIds)) {
            throw new InvalidOptimizationRequestException("请回答本次计划中的全部问题后再生成提示词。");
        }
        List<PlanAnswer> canonical = plan.questions().stream()
                .map(question -> new PlanAnswer(
                        question.id(),
                        question.question(),
                        submitted.get(question.id()).answer().trim()
                ))
                .toList();
        return new ConfirmedPlan(canonical, plan.planningContext(), true);
    }

    /**
     * 最终文件集合与计划前分析完全一致时复用快照，避免重复完整分析。
     */
    public Optional<ContextSnapshot> reusableContext(
            ConfirmedPlan confirmedPlan,
            ContextAnalysisRequest filteredContext,
            String analysisQuery
    ) {
        if (!confirmedPlan.bound() || confirmedPlan.planningContext() == null) {
            return Optional.empty();
        }
        PlanningSessionStore.ContextSession context = requireContext(confirmedPlan.planningContext());
        return Objects.equals(
                context.reference().version(),
                fingerprintContext(filteredContext, analysisQuery)
        )
                ? Optional.of(context.snapshot())
                : Optional.empty();
    }

    private PlanningSessionStore.ContextSession requireContext(PlanningContextReference reference) {
        PlanningSessionStore.ContextSession context = store.findContext(reference.contextId())
                .orElseThrow(() -> new InvalidOptimizationRequestException("文件上下文已过期，请重新分析。"));
        if (!Objects.equals(context.ownerUserId(), currentActor.require().userId())) {
            // 与不存在或过期使用同一提示，避免向其他用户泄露资源是否存在。
            throw new InvalidOptimizationRequestException("文件上下文已过期，请重新分析。");
        }
        if (!Objects.equals(context.reference().version(), reference.version())) {
            throw new InvalidOptimizationRequestException("文件上下文版本无效，请重新分析。");
        }
        return context;
    }

    private PlanningSessionStore.PlanSession requirePlan(String planId) {
        PlanningSessionStore.PlanSession plan = store.findPlan(planId)
                .orElseThrow(() -> new InvalidOptimizationRequestException("确认问题已过期，请重新生成。"));
        if (!Objects.equals(plan.ownerUserId(), currentActor.require().userId())) {
            // 与不存在或过期使用同一提示，避免向其他用户泄露资源是否存在。
            throw new InvalidOptimizationRequestException("确认问题已过期，请重新生成。");
        }
        return plan;
    }

    private List<PlanAnswer> validateLegacyAnswers(List<PlanAnswer> answers) {
        List<PlanAnswer> safeAnswers = answers == null ? List.of() : answers;
        Map<String, PlanAnswer> unique = new LinkedHashMap<>();
        for (PlanAnswer answer : safeAnswers) {
            if (answer == null || isBlank(answer.questionId()) || isBlank(answer.question()) || isBlank(answer.answer())) {
                throw new InvalidOptimizationRequestException("计划确认包含未回答的问题。");
            }
            if (unique.putIfAbsent(answer.questionId(), answer) != null) {
                throw new InvalidOptimizationRequestException("同一个计划问题不能重复回答。");
            }
        }
        return List.copyOf(unique.values());
    }

    private PlanningContextDigest buildDigest(ContextSnapshot snapshot) {
        List<String> technologies = snapshot.technologyStack().stream()
                .limit(20)
                .map(item -> truncate(item.name()))
                .toList();
        List<String> dependencies = snapshot.dependencies().stream()
                .limit(40)
                .map(item -> truncate(item.ecosystem() + ":" + item.name()
                        + (isBlank(item.version()) ? "" : "@" + item.version())))
                .toList();
        List<String> directoryOverview = snapshot.directoryTree().stream()
                .limit(80)
                .map(this::truncate)
                .toList();
        List<String> fileSummaries = snapshot.fileSnippets().stream()
                .limit(30)
                .map(file -> truncate(file.path() + "：" + (isBlank(file.summary())
                        ? "已识别为 " + file.language() + " 文件"
                        : file.summary())))
                .toList();
        List<String> warnings = snapshot.warnings().stream()
                .filter(value -> !containsSensitiveWarning(value))
                .limit(20)
                .map(this::truncate)
                .toList();
        int analyzedFileCount = snapshot.fileCoverage().isEmpty()
                ? snapshot.fileSnippets().size()
                : (int) snapshot.fileCoverage().stream()
                        .filter(item -> !"FAILED".equals(item.extractionStatus()))
                        .count();
        return new PlanningContextDigest(
                truncate(snapshot.customDescription()),
                technologies,
                dependencies,
                directoryOverview,
                fileSummaries,
                snapshot.analysisStatus(),
                analyzedFileCount,
                warnings
        );
    }

    private String fingerprintContext(ContextAnalysisRequest request, String analysisQuery) {
        MessageDigest digest = sha256();
        update(digest, safe(analysisQuery).trim());
        update(digest, request.customDescription());
        List<ContextFileInput> files = new ArrayList<>(request.files());
        files.sort(Comparator.comparing(file -> safe(file.path())));
        for (ContextFileInput file : files) {
            update(digest, file.path());
            update(digest, file.language());
            update(digest, file.documentId());
            update(digest, file.sizeBytes() == null ? "" : file.sizeBytes().toString());
            update(digest, file.content());
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private String fingerprintContextOwner(String rawPrompt, String contextDescription) {
        MessageDigest digest = sha256();
        update(digest, safe(rawPrompt).trim());
        update(digest, safe(contextDescription).trim());
        return HexFormat.of().formatHex(digest.digest());
    }

    private String fingerprintPlanInput(
            String rawPrompt,
            String contextDescription,
            List<ConversationMessage> conversationHistory
    ) {
        MessageDigest digest = sha256();
        update(digest, safe(rawPrompt).trim());
        update(digest, safe(contextDescription).trim());
        List<ConversationMessage> messages = conversationHistory == null ? List.of() : conversationHistory;
        for (ConversationMessage message : messages) {
            if (message != null) {
                update(digest, safe(message.role()).trim());
                update(digest, safe(message.content()).trim());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        digest.update(safe(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private String truncate(String value) {
        String safeValue = safe(value).trim();
        return safeValue.length() <= MAX_DIGEST_ITEM_CHARACTERS
                ? safeValue
                : safeValue.substring(0, MAX_DIGEST_ITEM_CHARACTERS) + "…";
    }

    private boolean containsSensitiveWarning(String value) {
        String safeValue = safe(value);
        return safeValue.contains("受保护") || safeValue.contains("敏感")
                || safeValue.contains("密钥") || safeValue.contains("凭据");
    }

    private void rejectCredential(String value) {
        if (sensitiveValueDetector.containsCredential(value)) {
            throw new InvalidOptimizationRequestException("输入中疑似包含真实凭据，请移除后重试。");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record ResolvedPlanningContext(
            PlanningContextReference reference,
            PlanningContextDigest digest
    ) {
    }

    public record PlanRegistration(
            String planId,
            PlanningContextReference planningContext,
            Instant expiresAt
    ) {
    }

    public record ConfirmedPlan(
            List<PlanAnswer> answers,
            PlanningContextReference planningContext,
            boolean bound
    ) {

        public ConfirmedPlan {
            answers = List.copyOf(answers);
        }
    }
}
