package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.service.ContextAnalyzer;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.*;
import com.promptoptimizer.enhancement.service.PlanningSessionExpiredException;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.policy.domain.PlatformConstraintRules;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 新自然链的真实会话绑定、权限过滤及正文形态回归，不调用外部模型。 */
class NaturalEnhancementServiceTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final AtomicReference<ActorIdentity> identity = new AtomicReference<>();
    private final CurrentActor actor = identity::get;
    private final ContextAnalyzer analyzer = mock(ContextAnalyzer.class);
    private final StubProvider provider = new StubProvider();
    private NaturalEnhancementService service;
    private PlanningSessionServiceImpl sessions;
    private NaturalEnhancementSettings settings;

    @BeforeEach
    void setUp() {
        identity.set(new ActorIdentity(USER, new UUID(0, 1), new UUID(0, 3), "demo@local", "Fixture"));
        var filter = new ProtectedContextFilterImpl();
        var clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
        sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock), analyzer, filter, actor, clock);
        when(analyzer.analyze(any(), any())).thenReturn(context());
        var catalog = mock(PlatformModelCatalog.class);
        when(catalog.resolve(any())).thenReturn(new PlatformModelCatalog.ModelEntry(new UUID(0, 4),
                "fixture:model", "fixture", "model", "Fixture model", true, true, 0));
        settings = new NaturalEnhancementSettings();
        service = new NaturalEnhancementService(analyzer, filter, sessions, actor, catalog, provider, clock, settings);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"CORE_REPAIR,core-repair-v1", "ACTION_BOUNDARY_REPAIR,core-repair-v2", "COMPLETION_BASIS_REPAIR,core-repair-v3", "SIMPLIFIED,core-simplified-v1"})
    void coreRepairAppliesToNewDirectTrialAndPlanRequestsButExplicitHistoryKeepsItsVersion(String profile, String version) {
        settings.setProfile(NaturalEnhancementSettings.Profile.valueOf(profile));
        var direct = service.optimize(request("写一封邮件", null, List.of(), "fixture:model"));
        assertThat(direct.chainVersion()).isEqualTo(version);
        service.plan(planRequest());
        assertThat(provider.last.strategyVersion()).isEqualTo(version);
        service.optimizeTrial(request("写一封邮件", null, List.of(), "fixture:model"));
        assertThat(provider.last.strategyVersion()).isEqualTo(version);
        service.optimize(request("写一封邮件", null, List.of(), "fixture:model"), "31");
        assertThat(provider.last.strategyVersion()).isEqualTo("31");
        settings.setProfile(NaturalEnhancementSettings.Profile.STABLE);
        service.optimize(request("写一封邮件", null, List.of(), "fixture:model"));
        assertThat(provider.last.strategyVersion()).isEqualTo("31");
    }

    @ParameterizedTest
    @ValueSource(strings = {"写一封邮件", "编写一个Java函数", "查询全球使用AI最多的职业/行业"})
    void directGenerationHasCanonicalBodyWithoutFixedClausesOrSyntheticSections(String raw) {
        var result = service.optimize(request(raw, null, List.of(), "fixture:model"));
        assertThat(result.resultKind()).isEqualTo(PromptDocument.ResultKind.TEXT);
        assertThat(result.sections()).isNull();
        assertThat(result.optimizedPrompt()).startsWith("自然任务正文");
        assertThat(result.generationPipeline()).isEqualTo("NATURAL");
        assertThat(result.framework()).isEqualTo("ICIO");
        assertThat(result.constraintGroups().platformMandatory()).isEmpty();
        assertThat(result.appliedConstraints()).isEmpty();
        assertThat(result.optimizedPrompt()).isEqualTo(provider.body);
        assertThat(provider.last.mandatoryRules()).isEmpty();
        assertThat(provider.planCalls).isZero();
        assertThat(provider.last.rawPrompt()).isEqualTo(raw);
    }

    @Test
    void explicitTrialSharesGenerationButDoesNotRelaxFormalAuthenticationOrPlan() {
        CurrentActor denied = () -> { throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("fixture"); };
        var filter = new ProtectedContextFilterImpl();
        var clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
        var privateSessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock), analyzer, filter, denied, clock);
        var catalog = mock(PlatformModelCatalog.class);
        when(catalog.resolve(any())).thenReturn(new PlatformModelCatalog.ModelEntry(new UUID(0, 4),
                "fixture:model", "fixture", "model", "Fixture model", true, true, 0));
        var explicit = new NaturalEnhancementService(analyzer, filter, privateSessions, denied, catalog, provider, clock);
        var input = request("写一封邮件", null, List.of(), "fixture:model");
        assertThat(explicit.optimizeTrial(input).optimizedPrompt()).isEqualTo(service.optimize(input).optimizedPrompt());
        assertThatThrownBy(() -> explicit.optimize(input)).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        assertThatThrownBy(() -> explicit.plan(planRequest())).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        assertThatThrownBy(() -> privateSessions.confirm(input.rawPrompt(), "", List.of(), null))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }

    @Test
    void currentPlanKeepsEveryQuestionWithinTheExistingProtocolCapacity() {
        provider.questions = java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(i -> question("q" + i)).toList();
        assertThat(service.plan(planRequest()).questions()).hasSize(8);
        provider.questions = java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(i -> question("q" + i)).toList();
        assertThat(service.plan(planRequest()).questions()).hasSize(9);
        assertThat(service.plan(planRequest(), "3").questions()).hasSize(9);
        // 八题是模型指导，协议容量仍为64；超容量必须失败，不能截断后伪装成完整Plan。
        provider.questions = java.util.stream.IntStream.rangeClosed(1, 64).mapToObj(i -> question("q" + i)).toList();
        assertThat(service.plan(planRequest()).questions()).hasSize(64);
        provider.questions = java.util.stream.IntStream.rangeClosed(1, 65).mapToObj(i -> question("q" + i)).toList();
        assertThatThrownBy(() -> service.plan(planRequest())).isInstanceOf(ProviderException.class);
    }

    @Test
    void zeroQuestionPlanRemainsBoundAndGeneratesWithoutInventingAnswers() {
        var plan = service.plan(planRequest());
        assertThat(plan.questions()).isEmpty();
        assertThat(plan.planId()).isNotBlank();
        var result = service.optimize(request("写一封邮件", new PlanConfirmation(plan.planId(), null, List.of()),
                List.of(), "fixture:model"));
        assertThat(result.sections()).isNull();
        assertThat(provider.last.actualAnswers()).isEmpty();
        assertThat(result.constraintGroups().platformMandatory()).isEmpty();
        assertThat(result.optimizedPrompt()).isEqualTo(provider.body);
    }

    @Test
    void realQuestionTextComesFromServerAndAllAnswersReachGeneration() {
        provider.questions = IntStream.range(0, 12).mapToObj(i -> question("q" + i)).toList();
        var plan = service.plan(planRequest());
        var answers = plan.questions().stream().map(q -> new PlanAnswer(q.id(), "伪造的问题文案", "保留未知，不默认确认")).toList();
        var result = service.optimize(request("写一封邮件", new PlanConfirmation(plan.planId(), null, answers), List.of(), "fixture:model"));
        assertThat(provider.last.actualAnswers()).hasSize(12);
        assertThat(provider.last.actualAnswers()).allMatch(a -> a.question().equals("收件对象仍需明确？"));
        assertThat(provider.last.actualAnswers()).allMatch(a -> a.answer().equals("保留未知，不默认确认"));
        assertThat(result.constraintGroups().platformMandatory()).isEmpty();
        assertThat(provider.last.mandatoryRules()).isEmpty();
        assertThat(result.optimizedPrompt()).isEqualTo(provider.body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STABLE", "CORE_REPAIR", "ACTION_BOUNDARY_REPAIR", "COMPLETION_BASIS_REPAIR", "SIMPLIFIED"})
    void naturalModeKeepsOnlyExplicitAdditionalPermissionsWithoutDependingOnOptionalEnhancementFlags(String profile) {
        settings.setProfile(NaturalEnhancementSettings.Profile.valueOf(profile));
        var request = new OptimizationRequest("写一封邮件", new ContextAnalysisRequest("", List.of()),
                new EnhancementOptions(TemplateCode.AUTO, false, false, PromptFormat.COMPACT), List.of(),
                new PermissionPolicyInput(List.of("private/**"), List.of("发送邮件")), null, "fixture:model");
        var result = service.optimize(request);
        var extra = List.of("禁止读取或输出受保护路径：private/**。", "以下操作必须先获得人工确认：发送邮件。");
        assertThat(result.constraintGroups().platformMandatory()).isEmpty();
        assertThat(result.constraintGroups().additionalPermissions()).containsExactlyElementsOf(extra);
        assertThat(result.optimizedPrompt()).isEqualTo(provider.body + "\n\n" + String.join("\n", extra));
        assertThat(provider.last.mandatoryRules()).containsExactlyElementsOf(result.appliedConstraints());
    }

    @Test
    void optionalHintsAndDescriptionsDoNotRejectAValidQuestion() {
        provider.questions = List.of(new PlanQuestion("q1", "原定收件对象是谁？", null, PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("a", "客户", null, "现有客户", false),
                        new PlanOption("b", "内部成员", null, "内部成员", false)), List.of(), true));
        assertThat(service.plan(planRequest()).questions()).hasSize(1);
    }

    @Test
    void serverOptionsRejectForgedChoiceAndPreserveSemicolonsInsideMultipleChoice() {
        provider.questions = List.of(new PlanQuestion("q1", "保留哪些交付内容？", "影响交付范围", PlanQuestionType.MULTIPLE_CHOICE,
                List.of(new PlanOption("a", "邮件", "", "正文；保留已知事实", false),
                        new PlanOption("b", "附件说明", "", "解释原定附件", false)), List.of(), false));
        var plan = service.plan(planRequest());
        assertThatThrownBy(() -> service.optimize(request("写一封邮件", new PlanConfirmation(plan.planId(), null,
                List.of(new PlanAnswer("q1", "客户端题干", "新增未授权事实"))), List.of(), "fixture:model")))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThat(provider.enhanceCalls).isZero();
        var result = service.optimize(request("写一封邮件", new PlanConfirmation(plan.planId(), null,
                List.of(new PlanAnswer("q1", "客户端题干", "正文；保留已知事实；解释原定附件"))), List.of(), "fixture:model"));
        assertThat(result.confirmedPlanAnswers()).containsExactly(
                new PlanAnswer("q1", "保留哪些交付内容？", "正文；保留已知事实；解释原定附件"));
    }

    @Test
    void secondRetrievalKeepsEarlierPermittedEvidenceWithoutReintroducingExcludedPaths() {
        var earlier = new ContextSnapshot("", List.of(), List.of(), List.of("notes.md"),
                List.of(new com.promptoptimizer.context.domain.FileSnippet("notes.md", "markdown", "首次材料事实", "摘要", false)),
                List.of(), List.of(), "earlier");
        when(analyzer.analyze(any(), any())).thenReturn(earlier, context());
        var prepared = sessions.prepareContext(new com.promptoptimizer.context.dto.PlanningContextRequest(
                "写一封邮件", new ContextAnalysisRequest("", List.of(new ContextFileInput("notes.md", "首次材料事实", "markdown"))),
                PermissionPolicyInput.empty()));
        var reference = new PlanningContextReference(prepared.contextId(), prepared.version());
        provider.questions = List.of(question("q1"));
        var plan = service.plan(new OptimizationPlanRequest("写一封邮件", "", List.of(), reference, "fixture:model"));
        var confirmation = new PlanConfirmation(plan.planId(), reference,
                List.of(new PlanAnswer("q1", "伪题干", "客户")));
        service.optimize(request("写一封邮件", confirmation, List.of(), "fixture:model"));
        assertThat(provider.last.context().fileSnippets()).isEmpty();
        assertThat(provider.last.planningContext().fileSnippets()).extracting(f -> f.content()).containsExactly("首次材料事实");
        var protectedRequest = new OptimizationRequest("写一封邮件", new ContextAnalysisRequest("", List.of()),
                new EnhancementOptions(TemplateCode.AUTO, false, true, PromptFormat.COMPACT), List.of(),
                new PermissionPolicyInput(List.of("notes.md"), List.of()), confirmation, "fixture:model");
        service.optimize(protectedRequest);
        assertThat(provider.last.planningContext().fileSnippets()).isEmpty();
        assertThat(provider.last.planningContext().directoryTree()).isEmpty();
        assertThat(provider.last.planningContext().directoryTree()).isEmpty();
    }

    @Test
    void missingOrDuplicateAnswersNeverReachModel() {
        provider.questions = List.of(question("q1"), question("q2"));
        var plan = service.plan(planRequest());
        var one = new PlanAnswer("q1", "收件对象？", "客户");
        assertThatThrownBy(() -> service.optimize(request("写一封邮件",
                new PlanConfirmation(plan.planId(), null, List.of(one)), List.of(), "fixture:model")))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThatThrownBy(() -> service.optimize(request("写一封邮件",
                new PlanConfirmation(plan.planId(), null, List.of(one, one)), List.of(), "fixture:model")))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThat(provider.enhanceCalls).isZero();
    }

    @Test
    void changedTaskAndCrossUserCannotReusePlan() {
        var plan = service.plan(planRequest());
        var confirmation = new PlanConfirmation(plan.planId(), null, List.of());
        assertThatThrownBy(() -> service.optimize(request("改写另一个任务", confirmation, List.of(), "fixture:model")))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        identity.set(new ActorIdentity(new UUID(0, 99), new UUID(0, 1), new UUID(0, 3), "other@local", "Other"));
        assertThatThrownBy(() -> service.optimize(request("写一封邮件", confirmation, List.of(), "fixture:model")))
                .isInstanceOf(PlanningSessionExpiredException.class);
        assertThat(provider.enhanceCalls).isZero();
    }

    @Test
    void changedModelCannotSilentlyRebindConfirmedPlan() {
        var plan = service.plan(planRequest());
        assertThatThrownBy(() -> service.optimize(request("写一封邮件",
                new PlanConfirmation(plan.planId(), null, List.of()), List.of(), "other:model")))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThat(provider.enhanceCalls).isZero();
    }

    @Test
    void protectedFilesAreRemovedBeforeAnalysisAndProvider() {
        service.optimize(request("写一封邮件", null, List.of(new ContextFileInput(".env", "hidden", "text"),
                new ContextFileInput("README.md", "允许的资料", "markdown")), "fixture:model"));
        var argument = org.mockito.ArgumentCaptor.forClass(ContextAnalysisRequest.class);
        verify(analyzer).analyze(argument.capture(), any());
        assertThat(argument.getValue().files()).extracting(ContextFileInput::path).containsExactly("README.md");
    }

    @Test
    void credentialChecksRemainActiveWithoutVisibleFixedClauses() {
        assertThatThrownBy(() -> service.optimize(request("请处理 password=fixture-only-credential", null, List.of(), "fixture:model")))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        assertThat(provider.enhanceCalls).isZero();
        provider.body = "任务正文。password=fixture-only-credential";
        assertThatThrownBy(() -> service.optimize(request("写一封邮件", null, List.of(), "fixture:model")))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void oversizePlanFailsRatherThanSilentlyDroppingImportantQuestions() {
        provider.questions = IntStream.range(0, 65).mapToObj(i -> question("q" + i)).toList();
        assertThatThrownBy(() -> service.plan(planRequest())).isInstanceOf(ProviderException.class);
    }

    @Test
    void explicitStandalonePermissionIsAssembledOnceWithoutAddingFixedClauses() {
        String rule = "以下操作必须先获得人工确认：发送邮件。";
        provider.body = rule + "\n" + rule;
        var request = new OptimizationRequest("写一封邮件", new ContextAnalysisRequest("", List.of()),
                new EnhancementOptions(TemplateCode.AUTO, false, true, PromptFormat.COMPACT), List.of(),
                new PermissionPolicyInput(List.of(), List.of("发送邮件")), null, "fixture:model");
        var result = service.optimize(request);
        assertThat(result.optimizedPrompt()).isEqualTo(rule);
        assertThat(result.constraintGroups().platformMandatory()).isEmpty();
    }

    @Test
    void userExplicitClausesArePreservedRatherThanRemovedAsBoilerplate() {
        provider.body = "按以下明确要求完成任务。\n" + String.join("\n", PlatformConstraintRules.CODE_RULES);
        var result = service.optimize(request(provider.body, null, List.of(), "fixture:model"));
        assertThat(result.optimizedPrompt()).isEqualTo(provider.body);
        assertThat(provider.last.rawPrompt()).isEqualTo(provider.body);
        assertThat(result.constraintGroups().platformMandatory()).isEmpty();
    }

    @Test
    void quotedPlatformSentencesAndIndependentConditionsRemainIntact() {
        String rule = PlatformConstraintRules.TRUTH;
        provider.body = "保留原定交付。\n```text\n" + rule + "\n```\n若材料含冲突，保留两份来源，不扩大确认。";
        var result = service.optimize(request("写一封邮件", null, List.of(), "fixture:model"));
        assertThat(result.optimizedPrompt()).isEqualTo(provider.body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "3"})
    void legacyNaturalVersionKeepsItsProtocolWithoutFixedClausesAndUnknownVersionNeverCallsTheProvider(String version) {
        var result = service.optimize(request("写一封邮件", null, List.of(), "fixture:model"), version);
        assertThat(result.chainVersion()).isEqualTo(version);
        assertThat(provider.last.strategyVersion()).isEqualTo(version);
        assertThat(result.constraintGroups().platformMandatory()).isEmpty();
        assertThat(provider.last.mandatoryRules()).isEmpty();
        assertThat(result.optimizedPrompt()).isEqualTo(provider.body);
        int calls = provider.enhanceCalls;
        assertThatThrownBy(() -> service.optimize(request("写一封邮件", null, List.of(), "fixture:model"), "999"))
                .isInstanceOf(com.promptoptimizer.common.exception.UnsupportedResultVersionException.class);
        assertThat(provider.enhanceCalls).isEqualTo(calls);
    }

    @Test
    void questionsMustLinkToTheirActualDecisionWithoutInventingAConfirmation() {
        provider.questions = List.of(question("q1"));
        provider.decisions = List.of();
        assertThatThrownBy(() -> service.plan(planRequest())).isInstanceOf(ProviderException.class);
        provider.decisions = List.of(new NaturalTaskUnderstanding.OpenDecision("收件对象", "影响交付", "absent"));
        assertThatThrownBy(() -> service.plan(planRequest())).isInstanceOf(ProviderException.class);
        provider.decisions = null;
        var plan = service.plan(planRequest());
        service.optimize(request("写一封邮件", new PlanConfirmation(plan.planId(), null,
                List.of(new PlanAnswer("q1", "伪题干", "不知道，请保留可选择范围"))), List.of(), "fixture:model"));
        assertThat(provider.last.actualAnswers().getFirst().answer()).isEqualTo("不知道，请保留可选择范围");
        assertThat(provider.last.taskSources().stream().filter(s -> s.id().equals("actualAnswers.0")).findFirst().orElseThrow().text())
                .endsWith("不知道，请保留可选择范围");
    }

    @Test
    void unknownUserOwnedInformationMustNotRecommendAnInventedActualValue() {
        provider.questions = List.of(new PlanQuestion("q1", "实际需要支持的并发量是多少？", "影响容量判断", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("n100", "100", "尚未提供的实际量", "实际并发量为100", true, "该数值常见"),
                        new PlanOption("unknown", "暂不确定", "保留未知", "实际并发量尚未确定", false)), List.of(), true));
        assertThat(service.plan(planRequest()).questions().getFirst().options()).noneMatch(PlanOption::recommended);
    }

    @Test
    void suggestedExecutionChoiceRemainsAvailableWithoutPromotingOtherUnknownUserInformation() {
        var options = List.of(new PlanOption("a", "方式A", "依据任务推荐", "选择方式A", true, "便于当前交付"),
                new PlanOption("b", "方式B", "另一种方式", "选择方式B", false));
        provider.questions = List.of(new PlanQuestion("fact", "实际规模是多少？", "影响范围", PlanQuestionType.SINGLE_CHOICE, options, List.of(), true),
                new PlanQuestion("choice", "倾向哪种呈现方式？", "影响表达", PlanQuestionType.SINGLE_CHOICE, options, List.of(), true));
        provider.decisions = List.of(new NaturalTaskUnderstanding.OpenDecision("实际规模", "影响范围", "fact"),
                new NaturalTaskUnderstanding.OpenDecision("呈现偏好", "影响表达", "choice", NaturalTaskUnderstanding.DecisionKind.EXECUTION_CHOICE));
        var plan = service.plan(planRequest());
        assertThat(plan.questions()).hasSize(2);
        assertThat(plan.questions().getFirst().options()).noneMatch(PlanOption::recommended);
        assertThat(plan.questions().get(1).options().getFirst().recommended()).isTrue();
        assertThat(plan.questions().getFirst().options()).extracting(PlanOption::answer).containsExactly("选择方式A", "选择方式B");
    }

    @Test
    void replaysFrozenActualDraftsWithoutChangingBodyOrAddingUnprovenScopeInstructions() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var path = java.nio.file.Path.of("../../docs/testing/evidence/natural-task-generalization-2026-10-19/candidate2-transports.jsonl");
        int replayed = 0;
        for (String line : java.nio.file.Files.readAllLines(path)) {
            var trace = mapper.readTree(line);
            if (!trace.path("jobId").asText().endsWith("_R2_GEN")) continue;
            String user = trace.path("request").path("messages").get(1).path("content").asText();
            String raw = mapper.readTree(user.substring(user.indexOf('{'))).path("rawPrompt").asText();
            var body = mapper.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
            provider.body = body.path("optimizedPrompt").asText();
            provider.understanding = mapper.treeToValue(body.path("taskUnderstanding"), NaturalTaskUnderstanding.class);
            var result = service.optimize(request(raw, null, List.of(), "fixture:model"), "3");
            assertThat(result.optimizedPrompt()).isEqualTo(provider.body)
                    .doesNotContain("按原定交付的格式和篇幅落实以下边界");
            assertThat(result.appliedConstraints()).isEmpty();
            assertThat(result.constraintGroups().platformMandatory()).isEmpty();
            replayed++;
        }
        assertThat(replayed).isEqualTo(3);
    }

    private OptimizationRequest request(String raw, PlanConfirmation confirmation, List<ContextFileInput> files, String model) {
        return new OptimizationRequest(raw, new ContextAnalysisRequest("", files),
                new EnhancementOptions(TemplateCode.AUTO, false, true, PromptFormat.COMPACT),
                List.of(), PermissionPolicyInput.empty(), confirmation, model);
    }
    private OptimizationPlanRequest planRequest() { return new OptimizationPlanRequest("写一封邮件", "", List.of(), null, "fixture:model"); }
    private PlanQuestion question(String id) { return new PlanQuestion(id, "收件对象仍需明确？", "影响措辞", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true); }
    private ContextSnapshot context() { return new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"); }

    private static class StubProvider implements NaturalPromptProvider {
        List<PlanQuestion> questions = List.of();
        List<NaturalTaskUnderstanding.OpenDecision> decisions;
        NaturalTaskUnderstanding understanding;
        String body = "自然任务正文，保留独立条件、范围和原定交付物。";
        int planCalls;
        int enhanceCalls;
        NaturalPromptInput last;
        public <T> T enhance(NaturalPromptInput input, Function<NaturalPromptDraft, T> validation) {
            enhanceCalls++;
            last = input;
            return validation.apply(new NaturalPromptDraft(body, NaturalPromptDraft.Framework.ICIO, new ProviderMetadata("mock", "fixture:model", true),
                    understanding == null ? task(input, false) : understanding));
        }
        public <T> T plan(NaturalPromptInput input, Function<PlanningProviderResponse, T> validation) {
            planCalls++;
            return validation.apply(new PlanningProviderResponse("按原目标交付", questions, "mock", "fixture:model", true,
                    task(input, true)));
        }

        /** 会话与组装夹具只提供合法协议，不能以同一通用正文锚点证明原任务语义已实现。 */
        private NaturalTaskUnderstanding task(NaturalPromptInput input, boolean planning) {
            var open = planning ? (decisions == null
                    ? questions.stream().map(q -> new NaturalTaskUnderstanding.OpenDecision(q.question(), "影响交付", q.id())).toList()
                    : decisions) : List.<NaturalTaskUnderstanding.OpenDecision>of();
            if (!NaturalPromptInstructions.requiresTaskReview(input.strategyVersion())) {
                return new NaturalTaskUnderstanding(input.rawPrompt(), "原定交付", List.of(), List.of(), open);
            }
            var requirements = input.taskSources().stream()
                    .filter(source -> source.kind() == NaturalTaskSource.Kind.ORIGINAL_REQUEST || source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER)
                    .map(source -> new NaturalTaskUnderstanding.Requirement(source.text(),
                            List.of(new NaturalTaskUnderstanding.Evidence(source.id(), source.text())),
                            List.of(NaturalTaskUnderstanding.Dimension.GOAL))).toList();
            var coverage = planning ? List.<NaturalTaskReview.Coverage>of() : IntStream.range(0, requirements.size())
                    .mapToObj(i -> new NaturalTaskReview.Coverage(i, List.of(body.strip().lines().findFirst().orElseThrow()))).toList();
            var impacts = planning ? questions.stream().map(q -> new NaturalTaskReview.PlanImpact(q.id(),
                    "现有输入未说明用户保留的信息", "不同实际回答影响该题所指的交付范围")).toList() : List.<NaturalTaskReview.PlanImpact>of();
            return new NaturalTaskUnderstanding(input.rawPrompt(), "原定交付", requirements, List.of(), open,
                    new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, coverage, List.of(), impacts));
        }
    }
}
