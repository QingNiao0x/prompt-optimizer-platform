package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;

import com.promptoptimizer.enhancement.service.PlanningSessionService;
import com.promptoptimizer.enhancement.service.OptimizationPlanningService;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.provider.infrastructure.MockPromptPlanningProvider;
import com.promptoptimizer.policy.service.ProtectedContextFilter;
import com.promptoptimizer.template.service.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptimizationPlanningServiceTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            多条有效记录按调查日期降序、ID降序选一条。|展示多条有效记录供用户选择，用户选定后再查询详情并填充。
            中文方法提纲不超过1200字，附表另计。|1200字包含正文、附表标题、表注和表格内容。
            参考寿命表尚未确定，确认前不得计算YLL。|参考寿命表未确定，方案中先指定采用WHO标准寿命表，待用户确认后替换。
            研究Plan问答对提示词质量的影响。|Plan模式界定为：模型先输出任务计划或步骤，再据此生成最终回答；方法部分按此描述实验条件。
            研究Plan问答对提示词质量的影响。|Plan模式界定为：由研究者或用户提供计划文本，作为提示词的一部分输入模型；方法部分按此描述实验条件。
            只输出分析方案与SQL伪代码。|只输出SQL伪代码，不单独撰写分析方案说明。
            只输出分析方案与SQL伪代码。|只输出分析方案说明，不写SQL伪代码。
            """)
    void shouldRepairNewRealOptionReversalsWithinTheExistingBudget(String raw, String invalid) {
        AtomicInteger calls = new AtomicInteger();
        var planning = new OptimizationPlanningServiceImpl(request -> {
            String answer = calls.incrementAndGet() == 1 ? invalid : "沿用原始需求中已明确的规则，不自行修改。";
            var question = new PlanQuestion("detail", "还有哪些实施细节需要补充？", "", PlanQuestionType.SINGLE_CHOICE,
                    List.of(new PlanOption("keep", "沿用要求", "", answer, false),
                            new PlanOption("unknown", "暂不确定", "", "暂不确定", false)), List.of(), true);
            return new PlanningProviderResponse("确认未决细节", List.of(question), "mock", "planner", true);
        }, new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);
        var plan = planning.plan(request(raw));
        assertThat(calls).hasValue(2);
        assertThat(plan.questions()).allSatisfy(question -> assertThat(question.options())
                .noneMatch(option -> option.answer().equals(invalid)));
    }

    @Test
    void shouldNotShowAReversedLabelEvenWhenItsStoredAnswerIsCorrect() {
        var planning = new OptimizationPlanningServiceImpl(request -> new PlanningProviderResponse(
                "确认匹配策略", List.of(new PlanQuestion("duplicate", "存在多条匹配记录时如何选择？", "",
                PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("first", "移除保持原值的逻辑选项", "", "取消时保持原值。", true),
                new PlanOption("select", "手动选择", "", "手动选择匹配记录。", false)), List.of(), true)),
                "mock", "planner", true), new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);
        assertThatThrownBy(() -> planning.plan(request("完善基线匹配，取消时保持原值。")))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure ->
                        assertThat(failure.getField()).isEqualTo("questions.options.label"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"无需用户确认，直接填充。", "匹配到当前地区基线时直接自动填充基本信息，不额外弹窗确认。"})
    void shouldRepairAnOptionThatContradictsTheExplicitRuleBeforeRegisteringPlan(String invalidAnswer) {
        AtomicInteger calls = new AtomicInteger();
        var planning = new OptimizationPlanningServiceImpl(request -> {
            String answer = calls.incrementAndGet() == 1 ? invalidAnswer : "经用户确认后填充。";
            var question = new PlanQuestion("duplicate", "存在多条匹配记录时如何选择？", "影响记录选择", PlanQuestionType.SINGLE_CHOICE,
                    List.of(new PlanOption("first", "选第一条", "保持原始确认规则", answer, false),
                            new PlanOption("select", "手动选择", "由用户决定记录", "手动选择匹配记录后，经用户确认后填充。", false)),
                    List.of(), true);
            return new PlanningProviderResponse("确认匹配策略", List.of(question), "mock", "planner", true);
        }, new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);

        var plan = planning.plan(request("完善基线匹配，经用户确认后填充。"));
        assertThat(calls).hasValue(2);
        assertThat(plan.questions()).singleElement().satisfies(question ->
                assertThat(question.options()).allSatisfy(option -> assertThat(option.answer()).doesNotContain(invalidAnswer)));
    }

    @Test
    void shouldPassPublishedModelToPlanningProvider() {
        PlatformModelCatalog catalog = mock(PlatformModelCatalog.class);
        String modelId = "tokenhub:kimi-k3";
        when(catalog.resolve(modelId)).thenReturn(new PlatformModelCatalog.ModelEntry(
                UUID.randomUUID(), modelId, "tokenhub", "kimi-k3", "Kimi K3", true, false, 1));
        AtomicReference<String> receivedModel = new AtomicReference<>();
        OptimizationPlanningServiceImpl planning = new OptimizationPlanningServiceImpl(request -> {
            receivedModel.set(request.model());
            return new PlanningProviderResponse("无需额外确认", List.of(), "mock", "planner", true);
        }, new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);
        planning.setModelCatalog(catalog);

        planning.plan(new OptimizationPlanRequest("分析死亡率", "", List.of(), null, modelId));

        assertThat(receivedModel).hasValue(modelId);
    }

    @Test
    void shouldClassifyCurrentSoftwareGoalBeforeResearchMaterial() {
        OptimizationPlan plan = service.plan(new OptimizationPlanRequest(
                "开发订单接口", "附件是研究方案，仅用于说明订单业务规则", List.of()
        ));
        assertThat(plan.templateCode()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        assertThat(plan.questions()).extracting("id")
                .doesNotContain("research-region", "research-data", "research-tool");
    }

    @Test
    void shouldKeepResearchTaskWhenUserAsksForAnalysisCode() {
        OptimizationPlan plan = service.plan(new OptimizationPlanRequest(
                "研究广东省死亡率长期趋势，并提供可运行的分析代码", "", List.of()
        ));
        assertThat(plan.templateCode()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(plan.questions()).extracting("id").doesNotContain("software-done", "software-environment");
    }

    @Test
    void shouldTurnVerifiedCrossFileConflictsIntoRequiredPlanQuestions() {
        String rawPrompt = "按方案实现订单审批流程";
        PlanningSessionService sessions = new PlanningSessionServiceImpl(
                new InMemoryPlanningSessionStore(CLOCK),
                request -> new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                        new FileSnippet("src/main/resources/审批规则.txt", "text", "审批阈值：三万元", "现行审批阈值", false),
                        new FileSnippet("docs/新方案.txt", "text", "审批阈值：五万元", "新方案审批阈值", false)
                ), List.of(), List.of(), "test-v1"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        var preparation = sessions.prepareContext(new PlanningContextRequest(rawPrompt,
                new ContextAnalysisRequest("", List.of(
                        new ContextFileInput("src/main/resources/审批规则.txt", "审批阈值：三万元", "text"),
                        new ContextFileInput("docs/新方案.txt", "审批阈值：五万元", "text")
                )), PermissionPolicyInput.empty()));
        OptimizationPlanningService planning = new OptimizationPlanningServiceImpl(
                request -> new PlanningProviderResponse("已阅读规则。", List.of(), "mock", "planner", true),
                new PromptTemplateRegistryImpl(), sessions, CLOCK);

        OptimizationPlan plan = planning.plan(new OptimizationPlanRequest(rawPrompt, "", List.of(),
                new PlanningContextReference(preparation.contextId(), preparation.version())));

        assertThat(plan.questions()).singleElement().satisfies(question -> {
            assertThat(question.id()).startsWith("context-conflict-");
            assertThat(question.question()).contains("审批阈值", "三万元", "五万元", "请确认");
        });
    }

    @Test
    void shouldDescribeZeroQuestionsAsReadyForFinalGeneration() {
        OptimizationPlanningService noQuestionService = new OptimizationPlanningServiceImpl(
                request -> new PlanningProviderResponse("请回答下面的问题。", List.of(),
                        "mock", "planner", true),
                new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);
        OptimizationPlan plan = noQuestionService.plan(request("请把这段文字翻译为英语，保持原有段落格式。"));
        assertThat(plan.questions()).isEmpty();
        assertThat(plan.summary()).contains("无需额外确认");
    }

    @Test
    void shouldPreserveARealTwoOptionDecisionAndAnUnknownFactWithoutInventingRecommendations() {
        var questions = List.of(
                new PlanQuestion("region", "研究地区是哪里？", "填写实际范围", PlanQuestionType.FREE_TEXT,
                        List.of(), List.of("填写省市和纳入人群"), true),
                new PlanQuestion("delivery", "是否需要提供可运行代码？", "影响交付范围", PlanQuestionType.SINGLE_CHOICE,
                        List.of(new PlanOption("yes", "需要", "完整脚本", "提供代码", false),
                                new PlanOption("no", "不需要", "研究方案", "无需代码", false)), List.of(), true));
        var planning = new OptimizationPlanningServiceImpl(
                request -> new PlanningProviderResponse("还需确认范围和交付方式", questions, "mock", "planner", true),
                new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);
        var actual = planning.plan(request("分析死亡率"));
        assertThat(actual.questions()).hasSize(2);
        assertThat(actual.questions().getFirst().type()).isEqualTo(PlanQuestionType.FREE_TEXT);
        assertThat(actual.questions().get(1).options()).hasSize(2).noneMatch(PlanOption::recommended);
    }

    @Test
    void shouldRejectOversizedRecommendationReason() {
        var question = new PlanQuestion("tool", "用哪种分析工具？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("python", "Python", "", "Python", true, "a".repeat(301)),
                        new PlanOption("r", "R", "", "R", false)), List.of(), true);
        var planning = new OptimizationPlanningServiceImpl(
                request -> new PlanningProviderResponse("请确认工具", List.of(question), "mock", "planner", true),
                new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);
        assertThatThrownBy(() -> planning.plan(request("分析死亡率"))).isInstanceOf(ProviderException.class);
    }

    @Test
    void shouldRetryInvalidPlanExactlyOnceAndPreserveValidQuestions() {
        AtomicInteger calls = new AtomicInteger();
        OptimizationPlanningService service = new OptimizationPlanningServiceImpl(request -> {
            if (calls.incrementAndGet() == 1) {
                return new PlanningProviderResponse("", List.of(), "mock", "planner", true);
            }
            return new PlanningProviderResponse("请确认关键问题", List.of(new PlanQuestion(
                    "region", "研究地区是哪里？", "", PlanQuestionType.SINGLE_CHOICE,
                    List.of(
                            new com.promptoptimizer.enhancement.domain.PlanOption("pending", "未写明则待确认", "", "没写明的地区标为待确认。", true),
                            new com.promptoptimizer.enhancement.domain.PlanOption("gd", "广东省", "", "研究范围定为广东省。", false),
                            new com.promptoptimizer.enhancement.domain.PlanOption("bj", "北京市", "", "研究范围定为北京市。", false),
                            new com.promptoptimizer.enhancement.domain.PlanOption("delta", "长三角", "", "研究范围定为长三角。", false)
                    ), List.of(), true)), "mock", "planner", true);
        }, new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);
        assertThat(service.plan(request("研究死亡率" )).questions()).hasSize(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void shouldNotRetryNetworkOrCredentialFailures() {
        AtomicInteger calls = new AtomicInteger();
        OptimizationPlanningService service = new OptimizationPlanningServiceImpl(request -> {
            calls.incrementAndGet();
            throw new ProviderException(com.promptoptimizer.provider.domain.ProviderFailureType.UPSTREAM_UNAVAILABLE,
                    "连接失败", true);
        }, new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);
        assertThatThrownBy(() -> service.plan(request("研究死亡率"))).isInstanceOf(ProviderException.class);
        assertThat(calls).hasValue(1);
    }

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-13T12:00:00Z"),
            ZoneOffset.UTC
    );

    @ParameterizedTest
    @ValueSource(strings = {"例如：由研究者预先编写任务计划模板，模型按模板执行", "例如：先让模型输出分步计划再作答，计划不额外人工修订"})
    void shouldValidateFreeTextExamplesAgainstTheExplicitInteractiveProtocol(String badExample) {
        AtomicInteger calls = new AtomicInteger();
        var planning = new OptimizationPlanningServiceImpl(request -> {
            String example = calls.incrementAndGet() == 1 ? badExample : "例如：先提问，用户回答后再生成最终提示词";
            return new PlanningProviderResponse("确认试验流程", List.of(new PlanQuestion("protocol", "问答最多进行几轮？", "",
                    PlanQuestionType.FREE_TEXT, List.of(), List.of(example), true)), "mock", "planner", true);
        }, new PromptTemplateRegistryImpl(), planningSessions(CLOCK), CLOCK);
        var plan = planning.plan(request("设计研究方案，比较直接增强与 Plan 问答增强。"));
        assertThat(calls).hasValue(2);
        assertThat(plan.questions()).singleElement().satisfies(question -> assertThat(question.examples())
                .containsExactly("例如：先提问，用户回答后再生成最终提示词"));
    }

    private final OptimizationPlanningService service = new OptimizationPlanningServiceImpl(
            new MockPromptPlanningProvider(),
            new PromptTemplateRegistryImpl(),
            planningSessions(CLOCK),
            CLOCK
    );

    @Test
    void shouldAskReadableResearchQuestionsWithoutExposingInternalDimensions() {
        OptimizationPlan plan = service.plan(new OptimizationPlanRequest(
                """
                        我是一名科研工作者，想分析2015-2025年某地区心脑血管疾病死亡率特征，
                        包括长期趋势和季节性趋势、分性别地区人群和亚类比较、YLL与YLL率以及Arriaga分解。
                        """,
                "公共卫生研究",
                List.of()
        ));

        assertThat(plan.templateCode()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(plan.questions()).hasSize(6);
        assertThat(plan.questions()).extracting("question")
                .contains(
                        "这项研究具体覆盖哪个地区？",
                        "你将使用什么数据来源和文件格式？",
                        "心脑血管疾病亚类按什么标准划分？",
                        "除性别和地区外，还需要按哪些人群特征分组？",
                        "你希望使用哪种分析工具？",
                        "最终结果是否需要包含可运行的代码？"
                )
                .allSatisfy(question -> assertThat((String) question)
                        .doesNotContain("缺失维度", "TemplateCode", "INPUT", "OUTPUT", "ACCEPTANCE"));
        assertThat(plan.questions()).allSatisfy(question -> {
            if (question.type() == PlanQuestionType.FREE_TEXT) {
                assertThat(question.options()).isEmpty();
                assertThat(question.allowCustomAnswer()).isTrue();
                return;
            }
            assertThat(question.options()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(question.options()).filteredOn(com.promptoptimizer.enhancement.domain.PlanOption::recommended)
                    .hasSizeLessThanOrEqualTo(1);
        });
        assertThat(plan.questions().getFirst().type()).isEqualTo(PlanQuestionType.FREE_TEXT);
        assertThat(plan.questions().get(1).options()).noneMatch(PlanOption::recommended);
        assertThat(plan.questions().get(4).options()).extracting("label")
                .contains("R", "Python", "SPSS");
    }

    @Test
    void shouldRejectInternalImplementationTermsInProviderCopy() {
        OptimizationPlanningService invalidService = new OptimizationPlanningServiceImpl(
                request -> new PlanningProviderResponse(
                        "请选择 FEATURE_DEVELOPMENT 模板。",
                        List.of(),
                        "mock",
                        "invalid-planner",
                        true
                ),
                new PromptTemplateRegistryImpl(),
                planningSessions(Clock.systemUTC()),
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> invalidService.plan(request("分析一组数据")))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(
                                ProviderResponseValidationException.Reason.PLAN_STRUCTURE_INVALID));
    }

    @Test
    void shouldRejectMoreThanEightProviderQuestions() {
        List<PlanQuestion> questions = IntStream.range(0, 9)
                .mapToObj(index -> new PlanQuestion(
                        "question-" + index,
                        "请确认第 " + index + " 项信息？",
                        "这会影响最终结果。",
                        PlanQuestionType.SINGLE_CHOICE,
                        List.of(
                                new PlanOption("yes", "是", "采用", "是。", false),
                                new PlanOption("no", "否", "不采用", "否。", false)
                        ),
                        List.of(),
                        false
                ))
                .toList();
        OptimizationPlanningService invalidService = new OptimizationPlanningServiceImpl(
                request -> new PlanningProviderResponse("还需要确认一些信息。", questions, "mock", "planner", true),
                new PromptTemplateRegistryImpl(),
                planningSessions(Clock.systemUTC()),
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> invalidService.plan(request("分析一组数据")))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void shouldUseBackgroundDescriptionWhenInferringTheInternalStrategy() {
        OptimizationPlan plan = service.plan(new OptimizationPlanRequest(
                "请帮我完成这项分析",
                "心脑血管疾病死亡率科研项目",
                List.of()
        ));

        assertThat(plan.templateCode()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(plan.questions()).extracting("id").contains("research-data", "research-tool");
    }

    @Test
    void shouldRejectMultipleChoiceAnswersThatCannotFitTheConfirmationContract() {
        PlanQuestion oversized = new PlanQuestion(
                "oversized",
                "请选择需要的内容？",
                "可多选。",
                PlanQuestionType.MULTIPLE_CHOICE,
                List.of(
                        new PlanOption("first", "第一项", "说明", "a".repeat(800), false),
                        new PlanOption("second", "第二项", "说明", "b".repeat(800), false)
                ),
                List.of(),
                false
        );
        OptimizationPlanningService invalidService = new OptimizationPlanningServiceImpl(
                request -> new PlanningProviderResponse(
                        "还需要确认一项信息。",
                        List.of(oversized),
                        "mock",
                        "planner",
                        true
                ),
                new PromptTemplateRegistryImpl(),
                planningSessions(Clock.systemUTC()),
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> invalidService.plan(request("分析一组数据")))
                .isInstanceOf(ProviderException.class);
    }

    private OptimizationPlanRequest request(String prompt) {
        return new OptimizationPlanRequest(prompt, "", List.of());
    }

    @Test
    void shouldUseUploadedStatusBoundaryToRepairAPlanCandidateWithinTheSharedBudget() {
        String raw = "制定表单补值实现方案，地区缺失时的候选处理方式尚未决定。";
        String source = "方案应把无法核验的记录与确无候选、明确地区不符分别说明。";
        PlanningSessionService sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(CLOCK),
                request -> new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                        new FileSnippet("materials/software/current-brief.md", "markdown", source, source, false)),
                        List.of(), List.of(), "test-v1"), new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        var prepared = sessions.prepareContext(new PlanningContextRequest(raw, new ContextAnalysisRequest("", List.of(
                new ContextFileInput("materials/software/current-brief.md", source, "markdown"))), PermissionPolicyInput.empty()));
        AtomicInteger calls = new AtomicInteger();
        var planning = new OptimizationPlanningServiceImpl(request -> {
            String answer = calls.incrementAndGet() == 1 ? "无法核验地区时按查询失败处理。" : "无法核验地区时展示但不允许补值，并分别说明。";
            return new PlanningProviderResponse("确认地区缺失处理", List.of(new PlanQuestion("region-missing",
                    "无法核验地区的候选如何处理？", "影响补值候选", PlanQuestionType.SINGLE_CHOICE,
                    List.of(new PlanOption("review", "核验候选", "", answer, false),
                            new PlanOption("unknown", "暂不确定", "", "暂不确定", false)), List.of(), true)), "mock", "planner", true);
        }, new PromptTemplateRegistryImpl(), sessions, CLOCK);
        var plan = planning.plan(new OptimizationPlanRequest(raw, "", List.of(),
                new PlanningContextReference(prepared.contextId(), prepared.version())));
        assertThat(calls).hasValue(2);
        assertThat(plan.questions()).singleElement().satisfies(question -> assertThat(question.options())
                .noneMatch(option -> option.answer().contains("按查询失败处理")));
    }

    @Test
    void shouldNotLetAnApprovalConflictSuppressRefundOrNewApprovalConditions() {
        String raw = "完善审批与退款流程，核对审批标准并补全退款条件";
        PlanningSessionService sessions = new PlanningSessionServiceImpl(
                new InMemoryPlanningSessionStore(CLOCK),
                request -> new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                        new FileSnippet("docs/现行审批.txt", "text", "审批标准：三万元", "现行审批标准", false),
                        new FileSnippet("docs/新审批.txt", "text", "审批标准：五万元", "新审批标准", false)
                ), List.of(), List.of(), "test-v1"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        var preparation = sessions.prepareContext(new PlanningContextRequest(raw,
                new ContextAnalysisRequest("", List.of(
                        new ContextFileInput("docs/现行审批.txt", "审批标准：三万元", "text"),
                        new ContextFileInput("docs/新审批.txt", "审批标准：五万元", "text")
                )), PermissionPolicyInput.empty()));
        var questions = List.of(
                new PlanQuestion("approval", "审批标准是什么？", "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true),
                new PlanQuestion("refund", "退款规则采用什么标准？", "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true),
                new PlanQuestion("urgent", "紧急订单的审批标准是什么？", "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true));
        var planning = new OptimizationPlanningServiceImpl(
                request -> new PlanningProviderResponse("确认各项口径", questions, "mock", "planner", true),
                new PromptTemplateRegistryImpl(), sessions, CLOCK);
        var actual = planning.plan(new OptimizationPlanRequest(raw, "", List.of(),
                new PlanningContextReference(preparation.contextId(), preparation.version())));
        assertThat(actual.questions()).extracting(PlanQuestion::id)
                .contains("refund", "urgent").doesNotContain("approval");
        assertThat(actual.questions()).filteredOn(question -> question.id().startsWith("context-conflict-"))
                .hasSize(1);
    }

    private static PlanningSessionService planningSessions(Clock clock) {
        return new PlanningSessionServiceImpl(
                new InMemoryPlanningSessionStore(clock),
                request -> new ContextSnapshot(
                        request.customDescription(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "test-v1"
                ),
                new ProtectedContextFilterImpl(),
                TestActors.currentActor(),
                clock
        );
    }
}
