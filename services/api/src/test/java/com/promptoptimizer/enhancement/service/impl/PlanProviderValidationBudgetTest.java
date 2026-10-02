package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.service.PlanningSessionStore;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 将真实 OpenAI 兼容适配器接到 Plan 服务，验证解析与业务校验共享三次上游预算。
 * HTTP 使用公开虚构响应替身；计划注册经过真实会话服务并在存储边界检查写入次数。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanProviderValidationBudgetTest {
    private static final String ENDPOINT = "https://model.example.com/chat/completions";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldShareThreeCallsAcrossParserAndPlanValidationAndRegisterOnlyAValidPlan(boolean thirdAttemptValid)
            throws Exception {
        var clientBuilder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(clientBuilder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-placeholder");
        properties.setModel("test-model");
        var provider = new OpenAiCompatiblePromptEnhancementProvider(clientBuilder.build(), mapper, properties);
        var store = mock(PlanningSessionStore.class);
        var actor = TestActors.currentActor();
        var sessions = new PlanningSessionServiceImpl(store,
                request -> new ContextSnapshot(request.customDescription(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), "test-v1"),
                new ProtectedContextFilterImpl(), actor, CLOCK);
        var service = new OptimizationPlanningServiceImpl(provider, new PromptTemplateRegistryImpl(), sessions, CLOCK);

        // 解析失败先消耗一次，第二次故意返回能解析但选项 ID 重复的结果；不能再另开三次业务重试。
        upstream.expect(requestTo(ENDPOINT)).andRespond(withSuccess(completion("not-json"), MediaType.APPLICATION_JSON));
        upstream.expect(requestTo(ENDPOINT)).andRespond(withSuccess(completion(planContent(true)), MediaType.APPLICATION_JSON));
        upstream.expect(requestTo(ENDPOINT)).andExpect(request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            assertThat(body).contains("PLAN_OPTION_INVALID", "questions.options", "整理社区活动执行方案");
        }).andRespond(withSuccess(completion(planContent(!thirdAttemptValid)), MediaType.APPLICATION_JSON));

        var request = new OptimizationPlanRequest("整理社区活动执行方案，尚未确定预算和验收要求。", "", List.of());
        if (thirdAttemptValid) {
            var plan = service.plan(request);
            assertThat(plan.provider().mock()).isFalse();
            assertThat(plan.questions()).usingRecursiveComparison()
                    .ignoringFields("options.recommended", "options.recommendationReason")
                    .isEqualTo(questions(false));
            assertThat(plan.questions().get(1).options()).hasSize(5).noneMatch(PlanOption::recommended);
            var saved = ArgumentCaptor.forClass(PlanningSessionStore.PlanSession.class);
            verify(store).savePlan(saved.capture());
            assertThat(saved.getValue().questions()).isEqualTo(plan.questions());
            assertThat(saved.getValue().ownerUserId()).isEqualTo(actor.require().userId());
            assertThat(saved.getValue().planId()).isEqualTo(plan.planId());
        } else {
            assertThatThrownBy(() -> service.plan(request))
                    .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure -> {
                        assertThat(failure.getReason()).isEqualTo(
                                ProviderResponseValidationException.Reason.PLAN_OPTION_INVALID);
                        assertThat(failure.getField()).isEqualTo("questions.options");
                    });
            verifyNoInteractions(store);
        }
        upstream.verify();
    }

    private String completion(String content) throws Exception {
        return mapper.writeValueAsString(Map.of("model", "test-model", "choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("role", "assistant", "content", content)))));
    }

    private String planContent(boolean duplicateOptionId) throws Exception {
        return mapper.writeValueAsString(Map.of("summary", "请确认尚未明确的活动要求", "questions", questions(duplicateOptionId)));
    }

    /** 合法回复保留自由填写和五个可多选方案，推荐标记冲突不应抹掉问题内容。 */
    private List<PlanQuestion> questions(boolean duplicateOptionId) {
        return List.of(
                new PlanQuestion("budget", "活动预算是多少？", "预算尚未确定时可以明确待确认", PlanQuestionType.FREE_TEXT,
                        List.of(), List.of("由活动负责人确认预算后补充"), true),
                new PlanQuestion("acceptance", "验收清单需要覆盖哪些异常场景？", "也可以补充新的异常条件",
                        PlanQuestionType.MULTIPLE_CHOICE, List.of(
                        new PlanOption("normal", "正常流程", "按计划完成", "验证正常流程", true),
                        new PlanOption(duplicateOptionId ? "normal" : "missing", "输入缺失", "必要输入缺失", "验证输入缺失", true),
                        new PlanOption("failure", "失败提示", "失败时的反馈", "验证失败提示", true),
                        new PlanOption("repeat", "重复操作", "重复提交的行为", "验证重复操作", true),
                        new PlanOption("recovery", "恢复处理", "恢复后的行为", "验证恢复后的处理", true)),
                        List.of(), true)
        );
    }
}
