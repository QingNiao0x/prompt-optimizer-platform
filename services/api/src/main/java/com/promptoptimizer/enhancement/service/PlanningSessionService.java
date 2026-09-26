package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.common.logging.LogCorrelation;
import com.promptoptimizer.common.logging.LogFields;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.context.service.ContextAnalyzer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.dto.PlanConfirmation;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PlanningContextPreparation;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.policy.service.ProtectedContextFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
 * <p>该模块只向计划 Provider 暴露裁剪摘要及有限的业务文档摘录；完整的脱敏上下文快照保存在带 TTL 的服务端会话中。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class PlanningSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlanningSessionService.class);

    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final int MAX_DIGEST_ITEM_CHARACTERS = 400;
    private static final int MAX_DOCUMENT_DIGEST_CHARACTERS = 1_000;

    private final PlanningSessionStore store;
    private final ContextAnalyzer contextAnalyzer;
    private final ProtectedContextFilter protectedContextFilter;
    private final CurrentActor currentActor;
    private final SensitiveValueDetector sensitiveValueDetector;
    private final PlanningFactCardExtractor factCardExtractor = new PlanningFactCardExtractor();
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
        long startedNanos = System.nanoTime();
        String contextId = UUID.randomUUID().toString();
        int inputFileCount = contextFileCount(request);
        long inputCharacters = contextCharacterCount(request);
        try (LogCorrelation.Scope ignored = LogCorrelation.bindWorkflow("planning-context", contextId)) {
            try {
                ActorIdentity actor = currentActor.require();
                rejectCredential(request.rawPrompt());
                rejectCredential(request.context().customDescription());

                ProtectedContextFilter.FilteredContext filtered = protectedContextFilter.filter(
                        request.context(),
                        request.permissionPolicy()
                );
                ContextSnapshot snapshot = protectedContextFilter.attachReport(
                        contextAnalyzer.analyze(prioritizeDocuments(filtered.request()), request.rawPrompt()),
                        filtered
                );
                PlanningContextReference reference = new PlanningContextReference(
                        contextId,
                        fingerprintContext(filtered.request(), request.rawPrompt())
                );
                Instant expiresAt = clock.instant().plus(SESSION_TTL);
                PlanningContextDigest digest = buildDigest(snapshot, request.rawPrompt());
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
                long latencyMs = Math.max(0, clock.millis() - startedAt);
                LOGGER.info("event=context.prepare.completed requestId={} workflowId={} modelCall=false "
                                + "inputFiles={} analyzedFiles={} protectedFiles={} inputCharacters={} warnings={} durationMs={}",
                        LogFields.value(MDC.get("requestId")),
                        LogFields.value(MDC.get("workflowId")),
                        inputFileCount,
                        filtered.request().files().size(),
                        filtered.protectedPaths().size(),
                        inputCharacters,
                        snapshot.warnings().size(),
                        latencyMs);
                return new PlanningContextPreparation(
                        reference.contextId(),
                        reference.version(),
                        digest,
                        snapshot,
                        expiresAt,
                        latencyMs
                );
            } catch (RuntimeException exception) {
                LOGGER.error("event=context.prepare.failed requestId={} workflowId={} modelCall=false "
                                + "inputFiles={} inputCharacters={} failureType={} durationMs={}",
                        LogFields.value(MDC.get("requestId")),
                        LogFields.value(MDC.get("workflowId")),
                        inputFileCount,
                        inputCharacters,
                        LogFields.value(exception.getClass().getSimpleName()),
                        Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L));
                throw exception;
            }
        }
    }

    private int contextFileCount(PlanningContextRequest request) {
        return request == null || request.context() == null || request.context().files() == null
                ? 0
                : request.context().files().size();
    }

    private long contextCharacterCount(PlanningContextRequest request) {
        if (request == null || request.context() == null || request.context().files() == null) {
            return 0;
        }
        return request.context().files().stream()
                .filter(Objects::nonNull)
                .map(ContextFileInput::content)
                .filter(Objects::nonNull)
                .mapToLong(String::length)
                .sum();
    }

    /** 把少量文档放到分析队列前部，避免代码文件先占满本次摘要预算。 */
    private ContextAnalysisRequest prioritizeDocuments(ContextAnalysisRequest request) {
        List<ContextFileInput> documents = request.files().stream()
                .filter(PlanningDigestSelector::isDocument).toList();
        if (documents.isEmpty()) return request;
        List<ContextFileInput> prioritized = new ArrayList<>(request.files().size());
        documents.stream().limit(4).forEach(prioritized::add);
        request.files().stream().filter(file -> !PlanningDigestSelector.isDocument(file))
                .forEach(prioritized::add);
        documents.stream().skip(4).forEach(prioritized::add);
        return new ContextAnalysisRequest(request.customDescription(), prioritized);
    }

    /**
     * 解析计划请求中的上下文引用，并确保它由同一份需求创建且尚未过期。
     */
    public ResolvedPlanningContext resolveForPlan(
            PlanningContextReference reference,
            String rawPrompt,
            String contextDescription
    ) {
        currentActor.require();
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
            List<PlanQuestion> questions,
            String modelId
    ) {
        ActorIdentity actor = currentActor.require();
        resolveForPlan(planningContext.reference(), rawPrompt, contextDescription);
        String planId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(SESSION_TTL);
        if (planningContext.reference() != null) {
            Instant contextExpiry = requireContext(planningContext.reference()).expiresAt();
            if (contextExpiry.isBefore(expiresAt)) expiresAt = contextExpiry;
        }
        store.savePlan(new PlanningSessionStore.PlanSession(
                planId,
                actor.userId(),
                fingerprintPlanInput(rawPrompt, contextDescription, conversationHistory),
                planningContext.reference(),
                questions,
                modelId,
                expiresAt
        ));
        return new PlanRegistration(planId, planningContext.reference(), expiresAt);
    }

    /** 兼容未显式选择模型的既有调用方。 */
    public PlanRegistration registerPlan(String rawPrompt, String contextDescription,
            List<ConversationMessage> conversationHistory, ResolvedPlanningContext planningContext,
            List<PlanQuestion> questions) {
        return registerPlan(rawPrompt, contextDescription, conversationHistory,
                planningContext, questions, null);
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
        currentActor.require();
        if (confirmation == null) {
            return new ConfirmedPlan(List.of(), null, false);
        }
        if (confirmation.planId() == null || confirmation.planId().isBlank()) {
            throw new InvalidOptimizationRequestException("计划编号不能为空，请重新生成确认问题。");
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
        PlanningContextDigest boundDigest = plan.planningContext() == null
                ? null
                : requireContext(plan.planningContext()).digest();

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
        return new ConfirmedPlan(canonical, plan.planningContext(), true, boundDigest, plan.modelId());
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

    /** 校验上下文引用的所有者、有效期和版本，不向其他用户透露资源是否存在。 */
    private PlanningSessionStore.ContextSession requireContext(PlanningContextReference reference) {
        UUID ownerId = currentActor.require().userId();
        PlanningSessionStore.ContextSession context = store.findContext(reference.contextId())
                .orElseThrow(() -> new PlanningSessionExpiredException("文件上下文已过期，请重新分析。"));
        if (!Objects.equals(context.ownerUserId(), ownerId) || !context.expiresAt().isAfter(clock.instant())) {
            // 与不存在或过期使用同一提示，避免向其他用户泄露资源是否存在。
            throw new PlanningSessionExpiredException("文件上下文已过期，请重新分析。");
        }
        if (!Objects.equals(context.reference().version(), reference.version())) {
            throw new InvalidOptimizationRequestException("文件上下文版本无效，请重新分析。");
        }
        return context;
    }

    /** 验证计划存在、尚未过期且属于当前登录用户，不返回计划中的问题或答案。 */
    public void assertAccessible(String planId) { requirePlan(planId); }

    private PlanningSessionStore.PlanSession requirePlan(String planId) {
        UUID ownerId = currentActor.require().userId();
        PlanningSessionStore.PlanSession plan = store.findPlan(planId)
                .orElseThrow(() -> new PlanningSessionExpiredException("确认问题已过期，请重新生成。"));
        if (!Objects.equals(plan.ownerUserId(), ownerId) || !plan.expiresAt().isAfter(clock.instant())) {
            // 与不存在或过期使用同一提示，避免向其他用户泄露资源是否存在。
            throw new PlanningSessionExpiredException("确认问题已过期，请重新生成。");
        }
        return plan;
    }

    /** 拒绝重复或无效的旧版回答，避免一次计划问题被多次覆盖。 */
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

    /**
     * 从完整分析快照选取与当前任务相关且有数量上限的安全摘要；未覆盖文件会生成提示，
     * 避免模型把摘要中的缺席误判为项目中不存在。
     */
    private PlanningContextDigest buildDigest(ContextSnapshot snapshot, String query) {
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
        List<String> fileSummaries = new ArrayList<>();
        int detailedDocuments = 0;
        int sourceExcerpts = 0;
        List<FileSnippet> selectedFiles = PlanningDigestSelector.select(snapshot.fileSnippets(), query, 30);
        for (FileSnippet file : selectedFiles) {
            boolean documentExcerpt = PlanningDigestSelector.isDocument(file) && detailedDocuments < 4;
            // Java、XML、SQL 以前只有文件名。与当前任务相关的源码和建表语句需要摘录，否则计划会再向用户索取已上传的代码。
            boolean sourceExcerpt = !documentExcerpt
                    && sourceExcerptEligible(file)
                    && PlanningDigestSelector.relevant(file, query)
                    && sourceExcerpts < 6;
            fileSummaries.add(planningFileSummary(file, query, documentExcerpt || sourceExcerpt));
            if (documentExcerpt) detailedDocuments++;
            if (sourceExcerpt) sourceExcerpts++;
        }
        PlanningFactCardExtractor.Extraction facts = factCardExtractor.extract(snapshot, query);
        List<String> detectedConflicts = new ContextConflictDetector().detect(snapshot, List.of());
        List<String> warnings = new ArrayList<>(java.util.stream.Stream.concat(
                        detectedConflicts.stream(), snapshot.warnings().stream())
                .filter(value -> !containsSensitiveWarning(value))
                .limit(19)
                .map(this::truncate)
                .toList());
        if (snapshot.fileSnippets().size() > fileSummaries.size()) {
            warnings.add("计划摘要仅覆盖 " + fileSummaries.size() + "/" + snapshot.fileSnippets().size()
                    + " 个已提取文件，按需求相关性和目录多样性选择；未覆盖内容不能视为不存在。");
        }
        if (facts.omittedCount() > 0) {
            warnings.add("计划事实卡片达到数量上限，另有 " + facts.omittedCount()
                    + " 条明确事实未进入本次计划；未展示的规则不能视为不存在。");
        }
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
                warnings,
                facts.cards()
        );
    }

    private static boolean sourceExcerptEligible(FileSnippet file) {
        String path = file.path() == null ? "" : file.path().toLowerCase(java.util.Locale.ROOT);
        String language = file.language() == null ? "" : file.language().toLowerCase(java.util.Locale.ROOT);
        return "java".equals(language) || "xml".equals(language) || "sql".equals(language)
                || path.endsWith(".java") || path.endsWith(".xml") || path.endsWith(".sql");
    }

    private String planningFileSummary(FileSnippet file, String query, boolean includeExcerpt) {
        String description = file.path() + "：" + (isBlank(file.summary())
                ? "已识别为 " + file.language() + " 文件"
                : file.summary());
        if (!includeExcerpt
                || sensitiveValueDetector.containsCredential(file.content())) {
            return truncate(description);
        }
        String excerpt = PlanningDocumentExcerpt.select(file.content(), query, 600);
        if (excerpt.isBlank()) return truncate(description);
        return truncate(truncate(description, 300) + "；业务摘录：" + excerpt,
                MAX_DOCUMENT_DIGEST_CHARACTERS);
    }

    /** 按稳定的字段和文件顺序计算输入摘要，用于绑定短期引用而不在标识中暴露正文。 */
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

    /** 将原始任务、描述与用户对话纳入计划指纹，防止计划被用于不同输入。 */
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
        return truncate(value, MAX_DIGEST_ITEM_CHARACTERS);
    }

    private String truncate(String value, int limit) {
        String safeValue = safe(value).trim();
        return safeValue.length() <= limit
                ? safeValue
                : safeValue.substring(0, limit) + "…";
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

    /**
     * 已通过所有权和有效期校验的上下文引用及安全摘要。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record ResolvedPlanningContext(
            PlanningContextReference reference,
            PlanningContextDigest digest
    ) {
    }

    /**
     * 新计划标识、关联上下文和过期时间。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record PlanRegistration(
            String planId,
            PlanningContextReference planningContext,
            Instant expiresAt
    ) {
    }

    /**
     * 用户确认后供最终增强流程消费的计划数据。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record ConfirmedPlan(
            List<PlanAnswer> answers,
            PlanningContextReference planningContext,
            boolean bound,
            PlanningContextDigest planningContextDigest,
            String modelId
    ) {

        public ConfirmedPlan {
            answers = List.copyOf(answers);
        }

        /** 兼容未绑定事实摘要的现有调用方。 */
        public ConfirmedPlan(List<PlanAnswer> answers, PlanningContextReference planningContext, boolean bound) {
            this(answers, planningContext, bound, null, null);
        }

        /** 兼容调用方在新增模型绑定之前构造的确认对象。 */
        public ConfirmedPlan(List<PlanAnswer> answers, PlanningContextReference planningContext,
                boolean bound, PlanningContextDigest planningContextDigest) {
            this(answers, planningContext, bound, planningContextDigest, null);
        }
    }
}
