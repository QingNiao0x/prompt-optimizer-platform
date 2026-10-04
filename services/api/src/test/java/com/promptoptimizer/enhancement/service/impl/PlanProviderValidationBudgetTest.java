package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
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

    /** 失败路径只指出固定字段，不回显问题值；修复请求应准确定位并继续共享三次预算。 */
    @ParameterizedTest
    @ValueSource(strings = {"id_format", "id_duplicate", "question_internal", "hint_internal", "options_count", "examples_count"})
    void shouldLocateQuestionEnvelopeFailuresWithoutLeakingValuesOrExtendingTheBudget(String scenario) throws Exception {
        var clientBuilder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(clientBuilder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-placeholder");
        properties.setModel("test-model");
        var provider = new OpenAiCompatiblePromptEnhancementProvider(clientBuilder.build(), mapper, properties);
        var store = mock(PlanningSessionStore.class);
        var sessions = new PlanningSessionServiceImpl(store, request -> new ContextSnapshot("", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "test-v1"), new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        var service = new OptimizationPlanningServiceImpl(provider, new PromptTemplateRegistryImpl(), sessions, CLOCK);
        String field = switch (scenario) {
            case "id_format", "id_duplicate" -> "questions.id";
            case "question_internal" -> "questions.question";
            case "hint_internal" -> "questions.hint";
            case "options_count" -> "questions.options";
            default -> "questions.examples";
        };
        var reason = scenario.equals("options_count") ? ProviderResponseValidationException.Reason.PLAN_OPTION_INVALID
                : ProviderResponseValidationException.Reason.PLAN_QUESTION_INVALID;
        String invalid = invalidQuestionEnvelope(scenario);
        upstream.expect(requestTo(ENDPOINT)).andRespond(withSuccess(completion(invalid), MediaType.APPLICATION_JSON));
        for (int attempt = 2; attempt <= 3; attempt++) {
            upstream.expect(requestTo(ENDPOINT)).andExpect(request -> {
                String body = ((MockClientHttpRequest) request).getBodyAsString();
                assertThat(body).contains(reason.name(), field);
            }).andRespond(withSuccess(completion(invalid), MediaType.APPLICATION_JSON));
        }
        assertThatThrownBy(() -> service.plan(new OptimizationPlanRequest("整理社区活动方案，预算与时间尚未确认。", "", List.of())))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure -> {
                    assertThat(failure.getReason()).isEqualTo(reason);
                    assertThat(failure.getField()).isEqualTo(field);
                    assertThat(failure.getModelAttempts()).isEqualTo(3);
                });
        verifyNoInteractions(store);
        upstream.verify();
    }

    /** 每个固定反例只破坏一个展示边界，便于区分 ID、内部术语和数量失败。 */
    private String invalidQuestionEnvelope(String scenario) throws Exception {
        var options = new java.util.ArrayList<PlanOption>();
        if (scenario.equals("options_count")) {
            for (int index = 0; index < 6; index++) options.add(new PlanOption("o" + index, "预算方案" + index, "", "采用预算方案" + index, false));
        }
        var examples = scenario.equals("examples_count") ? List.of("一", "二", "三", "四", "五") : List.<String>of();
        var question = new PlanQuestion(scenario.equals("id_format") ? "需确认预算" : "budget",
                scenario.equals("question_internal") ? "请确认缺失维度？" : "活动预算是多少？",
                scenario.equals("hint_internal") ? "先确认缺失维度。" : "预算会影响活动方案。",
                PlanQuestionType.FREE_TEXT, options, examples, true);
        var questions = scenario.equals("id_duplicate") ? List.of(question, question) : List.of(question);
        return mapper.writeValueAsString(Map.of("summary", "预算仍未确定。", "questions", questions));
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/Fill.ts", "src/Fill.tsx", "src/Fill.js", "src/Fill.jsx", "src/Fill.vue"})
    void shouldSendVerifiedDecisionsThroughTheRealAdapterWithoutAnotherModelCall(String sourcePath) throws Exception {
        var clientBuilder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(clientBuilder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-placeholder");
        properties.setModel("test-model");
        var provider = new OpenAiCompatiblePromptEnhancementProvider(clientBuilder.build(), mapper, properties);
        String code = "const BASELINE_FILL_FIELDS = ['name', 'idNumber', 'address'];";
        var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(CLOCK),
                request -> new ContextSnapshot("", List.of(), List.of(), List.of(),
                        List.of(new FileSnippet(sourcePath, "unknown", code, "基线填充字段定义", false)),
                        List.of(), List.of(), "test-v1"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        String raw = "完善基线匹配功能，经用户确认后填充 BASELINE_FILL_FIELDS；取消保持原值，补测试。";
        var prepared = sessions.prepareContext(new PlanningContextRequest(raw,
                new ContextAnalysisRequest("", List.of(new ContextFileInput(sourcePath, code, "unknown"))),
                PermissionPolicyInput.empty()));
        var service = new OptimizationPlanningServiceImpl(provider, new PromptTemplateRegistryImpl(), sessions, CLOCK);

        upstream.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String content = body.path("messages").get(1).path("content").asText();
            var payload = mapper.readTree(content.substring(content.indexOf('{')));
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(raw);
            assertThat(payload.path("knownDecisions")).hasSize(4);
            assertThat(payload.path("knownDecisions").get(0).path("kind").asText()).isEqualTo("FIELD_SCOPE");
            assertThat(payload.path("knownDecisions").get(0).path("sources").toString()).contains(sourcePath);
            assertThat(payload.path("knownDecisions").get(1).path("kind").asText()).isEqualTo("CANCEL_EFFECT");
            assertThat(payload.path("knownDecisions").get(2).path("kind").asText()).isEqualTo("TEST_LAYER_LOOKUP");
            assertThat(payload.path("knownDecisions").get(3).path("kind").asText()).isEqualTo("TEST_TARGET_LOOKUP");
        }).andRespond(withSuccess(completion("{\"summary\":\"已有规则足够，工程细节由执行时核查。\",\"questions\":[]}"), MediaType.APPLICATION_JSON));

        var plan = service.plan(new OptimizationPlanRequest(raw, "", List.of(),
                new PlanningContextReference(prepared.contextId(), prepared.version())));
        assertThat(plan.questions()).isEmpty();
        assertThat(plan.planId()).isNotBlank();
        upstream.verify();
    }

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRepairUnsafeNewConditionOptionsWithinTheExistingBudget(boolean repairable) throws Exception {
        var clientBuilder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(clientBuilder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-placeholder");
        properties.setModel("test-model");
        var provider = new OpenAiCompatiblePromptEnhancementProvider(clientBuilder.build(), mapper, properties);
        var store = spy(new InMemoryPlanningSessionStore(CLOCK));
        String source = "src/baseline.ts";
        String code = "export const BASELINE_FILL_FIELDS = ['name'];";
        var sessions = new PlanningSessionServiceImpl(store,
                request -> new ContextSnapshot("", List.of(), List.of(), List.of(),
                        List.of(new FileSnippet(source, "typescript", code, "基线实现", false)),
                        List.of(), List.of(), "test-v1"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        String raw = "完善基线匹配功能，按当前用户所属地区匹配，包含下级地区但排除其他同级地区。"
                + "空地区的提示方式尚未确定，不扩展业务范围。";
        var prepared = sessions.prepareContext(new PlanningContextRequest(raw,
                new ContextAnalysisRequest("", List.of(new ContextFileInput(source, code, "typescript"))),
                PermissionPolicyInput.empty()));
        var service = new OptimizationPlanningServiceImpl(provider, new PromptTemplateRegistryImpl(), sessions, CLOCK);
        var request = new OptimizationPlanRequest(raw, "", List.of(),
                new PlanningContextReference(prepared.contextId(), prepared.version()));
        var safeQuestion = new PlanQuestion("region-empty", "当前用户地区为空时，应如何提示？", "提示方式尚未确定。",
                PlanQuestionType.SINGLE_CHOICE, List.of(
                new PlanOption("quiet", "不匹配不弹窗", "", "地区为空时不查询，不弹窗。", false),
                new PlanOption("warn", "提示后手工录入", "", "地区为空时不查询，提示后允许手工录入。", false)), List.of(), true);
        var unsafeQuestion = new PlanQuestion(safeQuestion.id(), safeQuestion.question(), safeQuestion.hint(),
                safeQuestion.type(), List.of(safeQuestion.options().getFirst(),
                new PlanOption("ignore", "忽略地区条件", "", "地区为空时忽略地区条件，仍按姓名和身份证号查询。", false)),
                List.of(), true);
        String unsafe = mapper.writeValueAsString(Map.of("summary", "请确认空地区的提示方式。", "questions", List.of(unsafeQuestion)));
        String safe = mapper.writeValueAsString(Map.of("summary", "请确认空地区的提示方式。", "questions", List.of(safeQuestion)));
        upstream.expect(requestTo(ENDPOINT)).andRespond(withSuccess(completion(unsafe), MediaType.APPLICATION_JSON));
        upstream.expect(requestTo(ENDPOINT)).andExpect(http -> {
            String body = ((MockClientHttpRequest) http).getBodyAsString();
            assertThat(body).contains("RULE_CONFLICT", "questions.options.answer");
        }).andRespond(withSuccess(completion(repairable ? safe : unsafe), MediaType.APPLICATION_JSON));
        if (repairable) {
            var plan = service.plan(request);
            assertThat(plan.questions()).hasSize(1);
            assertThat(plan.questions().getFirst().options()).noneMatch(option -> option.id().equals("ignore"));
            verify(store).savePlan(any());
        } else {
            upstream.expect(requestTo(ENDPOINT)).andRespond(withSuccess(completion(unsafe), MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> service.plan(request)).isInstanceOfSatisfying(
                    ProviderResponseValidationException.class, failure -> {
                        assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.RULE_CONFLICT);
                        assertThat(failure.getModelAttempts()).isEqualTo(3);
                    });
            verify(store, never()).savePlan(any());
        }
        upstream.verify();
    }

    /** 缺失处理的业务选择不能改写已定状态定义；重试应明确该区别并继续遵守总预算。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRepairMissingStateCandidatesWithoutWeakeningTheRuleOrAddingCalls(boolean repairable) throws Exception {
        var clientBuilder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(clientBuilder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-placeholder");
        properties.setModel("test-model");
        var provider = new OpenAiCompatiblePromptEnhancementProvider(clientBuilder.build(), mapper, properties);
        var store = spy(new InMemoryPlanningSessionStore(CLOCK));
        var sessions = new PlanningSessionServiceImpl(store,
                request -> new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), "test-v1"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), CLOCK);
        var service = new OptimizationPlanningServiceImpl(provider, new PromptTemplateRegistryImpl(), sessions, CLOCK);
        String raw = "比较甲乙两组完成率，只交付方法和空结果表模板，不执行分析、不填实际结果。"
                + "缺失答卷如何处理尚未确定，确认前不得擅自选择口径。不得将未完成、缺失、零值相互混同。";
        var keepMissing = new PlanOption("separate", "单列缺失", "保留缺失状态。",
                "在方法与空表模板中单独列示缺失状态，不填实际结果。", false);
        var safeQuestion = new PlanQuestion("missing-handling", "缺失答卷是否计入分母？", "计入口径尚未确定。",
                PlanQuestionType.SINGLE_CHOICE, List.of(keepMissing,
                new PlanOption("include", "计入分母", "统计口径改变，不改变缺失状态。",
                        "在方法中定义缺失答卷计入分母，但保持缺失状态，不将其视为未完成或零值，不计算实际结果。", false)),
                List.of(), true);
        var unsafeQuestion = new PlanQuestion(safeQuestion.id(), safeQuestion.question(), safeQuestion.hint(),
                safeQuestion.type(), List.of(keepMissing,
                new PlanOption("incomplete", "视为未完成", "", "缺失答卷计入分母，并视为未完成，不计入完成数。", false)),
                List.of(), true);
        String unsafe = mapper.writeValueAsString(Map.of("summary", "请确认缺失答卷的计入口径。", "questions", List.of(unsafeQuestion)));
        String safe = mapper.writeValueAsString(Map.of("summary", "请确认缺失答卷的计入口径。", "questions", List.of(safeQuestion)));
        upstream.expect(requestTo(ENDPOINT)).andRespond(withSuccess(completion(unsafe), MediaType.APPLICATION_JSON));
        upstream.expect(requestTo(ENDPOINT)).andExpect(http -> {
            String body = ((MockClientHttpRequest) http).getBodyAsString();
            // 实际适配器须发送可操作的限定修复要求，不能仅凭之后的成功响应冒称已修复。
            assertThat(body).contains("RULE_CONFLICT", "questions.options.answer", "保持缺失状态", "不填实际结果");
        }).andRespond(withSuccess(completion(repairable ? safe : unsafe), MediaType.APPLICATION_JSON));
        var request = new OptimizationPlanRequest(raw, "", List.of());
        if (repairable) {
            var plan = service.plan(request);
            assertThat(plan.questions()).hasSize(1);
            assertThat(plan.questions().getFirst().options()).noneMatch(option -> option.id().equals("incomplete"));
            assertThat(plan.questions().getFirst().options()).anyMatch(option -> option.id().equals("include"));
            verify(store).savePlan(any());
        } else {
            upstream.expect(requestTo(ENDPOINT)).andRespond(withSuccess(completion(unsafe), MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> service.plan(request)).isInstanceOfSatisfying(
                    ProviderResponseValidationException.class, failure -> {
                        assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.RULE_CONFLICT);
                        assertThat(failure.getField()).isEqualTo("questions.options.answer");
                        assertThat(failure.getModelAttempts()).isEqualTo(3);
                    });
            verify(store, never()).savePlan(any());
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
