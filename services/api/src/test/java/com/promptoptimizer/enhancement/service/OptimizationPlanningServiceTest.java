package com.promptoptimizer.enhancement.service;

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
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.provider.infrastructure.MockPromptPlanningProvider;
import com.promptoptimizer.policy.service.ProtectedContextFilter;
import com.promptoptimizer.template.service.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;

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

    @Test
    void shouldPassPublishedModelToPlanningProvider() {
        PlatformModelCatalog catalog = mock(PlatformModelCatalog.class);
        String modelId = "tokenhub:kimi-k3";
        when(catalog.resolve(modelId)).thenReturn(new PlatformModelCatalog.ModelEntry(
                UUID.randomUUID(), modelId, "tokenhub", "kimi-k3", "Kimi K3", true, false, 1));
        AtomicReference<String> receivedModel = new AtomicReference<>();
        OptimizationPlanningService planning = new OptimizationPlanningService(request -> {
            receivedModel.set(request.model());
            return new PlanningProviderResponse("无需额外确认", List.of(), "mock", "planner", true);
        }, new PromptTemplateRegistry(), planningSessions(CLOCK), CLOCK);
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
        PlanningSessionService sessions = new PlanningSessionService(
                new InMemoryPlanningSessionStore(CLOCK),
                request -> new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                        new FileSnippet("src/main/resources/审批规则.txt", "text", "审批阈值：三万元", "现行审批阈值", false),
                        new FileSnippet("docs/新方案.txt", "text", "审批阈值：五万元", "新方案审批阈值", false)
                ), List.of(), List.of(), "test-v1"),
                new ProtectedContextFilter(), TestActors.currentActor(), CLOCK);
        var preparation = sessions.prepareContext(new PlanningContextRequest(rawPrompt,
                new ContextAnalysisRequest("", List.of(
                        new ContextFileInput("src/main/resources/审批规则.txt", "审批阈值：三万元", "text"),
                        new ContextFileInput("docs/新方案.txt", "审批阈值：五万元", "text")
                )), PermissionPolicyInput.empty()));
        OptimizationPlanningService planning = new OptimizationPlanningService(
                request -> new PlanningProviderResponse("已阅读规则。", List.of(), "mock", "planner", true),
                new PromptTemplateRegistry(), sessions, CLOCK);

        OptimizationPlan plan = planning.plan(new OptimizationPlanRequest(rawPrompt, "", List.of(),
                new PlanningContextReference(preparation.contextId(), preparation.version())));

        assertThat(plan.questions()).singleElement().satisfies(question -> {
            assertThat(question.id()).startsWith("context-conflict-");
            assertThat(question.question()).contains("审批阈值", "三万元", "五万元", "请确认");
        });
    }

    @Test
    void shouldDescribeZeroQuestionsAsReadyForFinalGeneration() {
        OptimizationPlanningService noQuestionService = new OptimizationPlanningService(
                request -> new PlanningProviderResponse("请回答下面的问题。", List.of(),
                        "mock", "planner", true),
                new PromptTemplateRegistry(), planningSessions(CLOCK), CLOCK);
        OptimizationPlan plan = noQuestionService.plan(request("请把这段文字翻译为英语，保持原有段落格式。"));
        assertThat(plan.questions()).isEmpty();
        assertThat(plan.summary()).contains("无需额外确认");
    }

    @Test
    void shouldRetryInvalidPlanExactlyOnceAndPreserveValidQuestions() {
        AtomicInteger calls = new AtomicInteger();
        OptimizationPlanningService service = new OptimizationPlanningService(request -> {
            if (calls.incrementAndGet() == 1) {
                return new PlanningProviderResponse("", List.of(), "mock", "planner", true);
            }
            return new PlanningProviderResponse("请确认关键问题", List.of(new PlanQuestion(
                    "region", "研究地区是哪里？", "", PlanQuestionType.FREE_TEXT,
                    List.of(), List.of(), true)), "mock", "planner", true);
        }, new PromptTemplateRegistry(), planningSessions(CLOCK), CLOCK);
        assertThat(service.plan(request("研究死亡率" )).questions()).hasSize(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void shouldNotRetryNetworkOrCredentialFailures() {
        AtomicInteger calls = new AtomicInteger();
        OptimizationPlanningService service = new OptimizationPlanningService(request -> {
            calls.incrementAndGet();
            throw new ProviderException(com.promptoptimizer.provider.domain.ProviderFailureType.UPSTREAM_UNAVAILABLE,
                    "连接失败", true);
        }, new PromptTemplateRegistry(), planningSessions(CLOCK), CLOCK);
        assertThatThrownBy(() -> service.plan(request("研究死亡率"))).isInstanceOf(ProviderException.class);
        assertThat(calls).hasValue(1);
    }

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-13T12:00:00Z"),
            ZoneOffset.UTC
    );

    private final OptimizationPlanningService service = new OptimizationPlanningService(
            new MockPromptPlanningProvider(),
            new PromptTemplateRegistry(),
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
        assertThat(plan.questions().get(0).type()).isEqualTo(PlanQuestionType.FREE_TEXT);
        assertThat(plan.questions().get(4).options()).extracting("label")
                .containsExactly("R", "Python", "SPSS");
    }

    @Test
    void shouldRejectInternalImplementationTermsInProviderCopy() {
        OptimizationPlanningService invalidService = new OptimizationPlanningService(
                request -> new PlanningProviderResponse(
                        "请选择 FEATURE_DEVELOPMENT 模板。",
                        List.of(),
                        "mock",
                        "invalid-planner",
                        true
                ),
                new PromptTemplateRegistry(),
                planningSessions(Clock.systemUTC()),
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> invalidService.plan(request("分析一组数据")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("确认问题格式无效");
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
        OptimizationPlanningService invalidService = new OptimizationPlanningService(
                request -> new PlanningProviderResponse("还需要确认一些信息。", questions, "mock", "planner", true),
                new PromptTemplateRegistry(),
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
        OptimizationPlanningService invalidService = new OptimizationPlanningService(
                request -> new PlanningProviderResponse(
                        "还需要确认一项信息。",
                        List.of(oversized),
                        "mock",
                        "planner",
                        true
                ),
                new PromptTemplateRegistry(),
                planningSessions(Clock.systemUTC()),
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> invalidService.plan(request("分析一组数据")))
                .isInstanceOf(ProviderException.class);
    }

    private OptimizationPlanRequest request(String prompt) {
        return new OptimizationPlanRequest(prompt, "", List.of());
    }

    private static PlanningSessionService planningSessions(Clock clock) {
        return new PlanningSessionService(
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
                new ProtectedContextFilter(),
                TestActors.currentActor(),
                clock
        );
    }
}
