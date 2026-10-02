package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回放公开虚构场景的真实 Flash 推荐标记故障，保证修复不删选项、不替用户确认答案。
 * fixture 只保留四份诊断响应中的验收题，不包含账号、凭据或实际用户材料。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanRecommendationRegressionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
    private static final String RAW_PROMPT = "为社区公开课准备执行方案。预算和具体日期尚未确定。"
            + "最终请给出清晰的验收清单，覆盖正常流程、输入缺失、失败提示、重复操作以及恢复后的处理。";

    @Test
    void shouldReplayFourCapturedFlashQuestionsWithoutRetryingOrLosingAdditionalDecisions() throws IOException {
        try (var input = getClass().getResourceAsStream("/fixtures/plan/recommendation-cardinality.json")) {
            assertThat(input).isNotNull();
            ObjectMapper mapper = new ObjectMapper();
            var cases = mapper.readTree(input);
            assertThat(cases.size()).isEqualTo(4);
            for (var sample : cases) {
                PlanQuestion source = mapper.treeToValue(sample.get("question"), PlanQuestion.class);
                assertThat(source.options()).filteredOn(PlanOption::recommended).hasSize(5);
                AtomicInteger calls = new AtomicInteger();
                var service = service(source, calls);

                var plan = service.plan(new OptimizationPlanRequest(RAW_PROMPT, "", List.of()));

                assertThat(calls).as(sample.get("name").asText()).hasValue(1);
                assertThat(plan.questions()).singleElement().satisfies(actual -> {
                    assertThat(actual).usingRecursiveComparison()
                            .ignoringFields("options.recommended", "options.recommendationReason")
                            .isEqualTo(source);
                    assertThat(actual.hint()).contains("还要");
                    assertThat(actual.type()).isEqualTo(PlanQuestionType.MULTIPLE_CHOICE);
                    assertThat(actual.allowCustomAnswer()).isTrue();
                    assertThat(actual.options()).hasSize(5).noneMatch(PlanOption::recommended);
                });
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PlanQuestionType.class, names = {"SINGLE_CHOICE", "MULTIPLE_CHOICE"})
    void shouldKeepOneEvidenceSupportedRecommendationAndEveryOriginalChoice(PlanQuestionType type) {
        PlanQuestion source = new PlanQuestion("ui", "是否使用现有组件库？", "请保留你的实际选择", type,
                List.of(new PlanOption("element", "Element Plus", "沿用组件", "采用 Element Plus", true, "模型建议"),
                        new PlanOption("antd", "Ant Design Vue", "另一种组件", "采用 Ant Design Vue", true, "模型建议")),
                List.of(), true);
        AtomicInteger calls = new AtomicInteger();

        var plan = service(source, calls).plan(new OptimizationPlanRequest(
                "开发管理界面，本次明确采用 Element Plus。", "", List.of()));

        assertThat(calls).hasValue(1);
        assertThat(plan.questions()).singleElement().satisfies(actual -> {
            assertThat(actual).usingRecursiveComparison()
                    .ignoringFields("options.recommended", "options.recommendationReason").isEqualTo(source);
            assertThat(actual.options()).filteredOn(PlanOption::recommended)
                    .extracting(PlanOption::id).containsExactly("element");
            assertThat(actual.options().getFirst().recommendationReason()).contains("原始需求");
        });
    }

    @Test
    void shouldRejectSensitiveRecommendationReasonBeforeAlignmentCanDiscardIt() {
        PlanQuestion source = new PlanQuestion("ui", "是否使用现有组件库？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("element", "Element Plus", "", "采用 Element Plus", true,
                                "api_key=sk-SyntheticCanary987654321"),
                        new PlanOption("antd", "Ant Design Vue", "", "采用 Ant Design Vue", true)), List.of(), true);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> service(source, calls).plan(
                new OptimizationPlanRequest("开发管理界面", "", List.of())))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, exception -> {
                    assertThat(exception.getReason()).isEqualTo(Reason.SENSITIVE_CONTENT);
                    assertThat(exception.getField()).isEqualTo("questions.options.recommendationReason");
                });
        assertThat(calls).hasValue(2);
    }

    @Test
    void shouldRejectDuplicateOptionIdsEvenWhenRecommendationsCouldBeRepaired() {
        PlanQuestion source = new PlanQuestion("ui", "是否使用现有组件库？", "", PlanQuestionType.MULTIPLE_CHOICE,
                List.of(new PlanOption("same", "Element Plus", "", "采用 Element Plus", true),
                        new PlanOption("same", "Ant Design Vue", "", "采用 Ant Design Vue", true)), List.of(), true);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> service(source, calls).plan(
                new OptimizationPlanRequest("开发管理界面", "", List.of())))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(Reason.PLAN_OPTION_INVALID));
        assertThat(calls).hasValue(2);
    }

    /** 使用真实 Plan 注册流程验证返回题目，模型调用替身只供应公开诊断题。 */
    private OptimizationPlanningServiceImpl service(PlanQuestion question, AtomicInteger calls) {
        var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(CLOCK),
                request -> new ContextSnapshot(request.customDescription(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), "test-v1"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        return new OptimizationPlanningServiceImpl(request -> {
            calls.incrementAndGet();
            return new PlanningProviderResponse("请确认会改变结果的事项", List.of(question), "test", "flash", false);
        }, new PromptTemplateRegistryImpl(), sessions, CLOCK);
    }
}
