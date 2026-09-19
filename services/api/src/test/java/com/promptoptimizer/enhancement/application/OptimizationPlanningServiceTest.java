package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.infrastructure.MockPromptPlanningProvider;
import com.promptoptimizer.policy.application.ProtectedContextFilter;
import com.promptoptimizer.template.application.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationPlanningServiceTest {

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
                clock
        );
    }
}
