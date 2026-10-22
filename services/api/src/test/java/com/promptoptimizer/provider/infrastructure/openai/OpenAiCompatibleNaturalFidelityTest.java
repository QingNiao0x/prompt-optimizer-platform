package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 在真实Provider边界验证来源约束审阅、局部修补、失败预算及最终回调。
 * 测试只证明阶段与保护契约，语义收益继续由同条件真实作品对照判断。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OpenAiCompatibleNaturalFidelityTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String ORIGINAL_BODY = "请写邮件，发布时间为周五。";
    private static final String CORRECTED_BODY = "请写邮件，发布时间尚未确定。";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-api-key");
        properties.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @Test
    void noIssuePreservesTheSameDraftAndBoundSourcesWhileFinalizingAfterReview() throws Exception {
        String body = "请写邮件。\n\n发布时间尚未确定。";
        var callbacks = new AtomicInteger();
        var firstDraft = new AtomicReference<NaturalPromptDraft>();
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(body, "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            assertThat(callbacks.get()).isEqualTo(1);
            JsonNode payload = taskInput(request);
            assertBoundSources(payload);
            assertThat(payload.path("fidelityDraft").size()).isEqualTo(3);
            assertThat(payload.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(body);
            assertThat(payload.path("fidelityDraft").has("framework")).isTrue();
            assertThat(payload.path("fidelityDraft").has("taskUnderstanding")).isTrue();
            assertThat(payload.path("fidelityDraft").has("provider")).isFalse();
            assertThat(payload.has("completionSuggestions")).isFalse();
        }).andRespond(withSuccess(response(noIssues()), MediaType.APPLICATION_JSON));
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft result;
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 71), (tenant, fact) -> observations.add(fact)))) {
            result = provider.enhance(input("17"), draft -> {
                callbacks.incrementAndGet();
                firstDraft.compareAndSet(null, draft);
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
        }
        assertThat(result).isSameAs(firstDraft.get());
        assertThat(result.optimizedPrompt()).isEqualTo(body);
        assertThat(callbacks.get()).isEqualTo(2);
        assertThat(observations).hasSize(2).allSatisfy(fact -> {
            assertThat(fact.successful()).isTrue();
            assertThat(fact.usage().totalTokens()).isEqualTo(15L);
        });
        server.verify();
    }

    @Test
    void preparationGenerationAndFidelityUseThreeDistinctCallsWithoutNewSources() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            JsonNode payload = taskInput(request);
            assertBoundSources(payload);
            assertThat(payload.has("completionSuggestions")).isFalse();
            assertThat(payload.has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response(json.writeValueAsString(Map.of("taskUnderstanding", task(true, null)))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            JsonNode payload = taskInput(request);
            assertBoundSources(payload);
            assertThat(payload.path("completionSuggestions").isObject()).isTrue();
            assertThat(payload.has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response(draftResponse(CORRECTED_BODY, "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            JsonNode payload = taskInput(request);
            assertBoundSources(payload);
            assertThat(payload.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(CORRECTED_BODY);
        }).andRespond(withSuccess(response(noIssues()), MediaType.APPLICATION_JSON));
        var result = provider.enhance(input("16"), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(CORRECTED_BODY);
        server.verify();
    }

    @Test
    void rejectedFirstDraftUsesItsOwnBoundedRepairAndNeverReachesFidelity() throws Exception {
        String first = response(draftResponse(CORRECTED_BODY, "发布时间尚未确定"));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(first, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(first, MediaType.APPLICATION_JSON));
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> provider.enhance(input("17"), draft -> {
            callbacks.incrementAndGet();
            throw new ProviderException(ProviderFailureType.INVALID_RESPONSE, "首稿未通过必要保护", false);
        })).isInstanceOf(ProviderException.class);
        assertThat(callbacks.get()).isEqualTo(2);
        server.verify();
    }

    @Test
    void sourceGroundedPatchReceivesFreshCoverageAndTheFinalValidationCallback() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(draftResponse(ORIGINAL_BODY, "发布时间为周五")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response(patch("actualAnswers.0", "发布时间还没定。", "发布时间为周五", "发布时间尚未确定")), MediaType.APPLICATION_JSON));
        var seenBodies = new ArrayList<String>();
        var firstDraft = new AtomicReference<NaturalPromptDraft>();
        var result = provider.enhance(input("17"), draft -> {
            seenBodies.add(draft.optimizedPrompt());
            firstDraft.compareAndSet(null, draft);
            var resolved = draft.taskUnderstanding().resolve(input("17"));
            resolved.review().validateEnhancement(resolved, draft.optimizedPrompt());
            return draft;
        });
        assertThat(seenBodies).containsExactly(ORIGINAL_BODY, CORRECTED_BODY);
        assertThat(result.optimizedPrompt()).isEqualTo(CORRECTED_BODY);
        assertThat(result.framework()).isEqualTo(firstDraft.get().framework());
        assertThat(result.provider()).isSameAs(firstDraft.get().provider());
        assertThat(result.taskUnderstanding().requirements()).hasSize(2);
        assertThat(result.taskUnderstanding().review().requirementCoverage().get(1).quotes())
                .containsExactly("发布时间尚未确定");
        server.verify();
    }

    @Test
    void invalidFidelityPatchRetriesOnlyTheReviewAndCountsAllThreeAttempts() throws Exception {
        String first = response(draftResponse(ORIGINAL_BODY, "发布时间为周五"));
        String invalid = response(patch("fidelityDraft", "发布时间还没定。", "发布时间为周五", "发布时间尚未确定"));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(first, MediaType.APPLICATION_JSON));
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo(ENDPOINT)).andExpect(request -> {
                JsonNode payload = taskInput(request);
                assertBoundSources(payload);
                assertThat(payload.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(ORIGINAL_BODY);
            }).andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
        }
        var observations = new ArrayList<ModelCallObservation>();
        var callbacks = new AtomicInteger();
        try (var ignored = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(
                new UUID(0, 72), (tenant, fact) -> observations.add(fact)))) {
            assertThatThrownBy(() -> provider.enhance(input("17"), draft -> {
                callbacks.incrementAndGet();
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            })).isInstanceOf(ProviderException.class);
        }
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(observations).hasSize(3).allSatisfy(fact -> assertThat(fact.usage().totalTokens()).isEqualTo(15L));
        assertThat(observations.stream().filter(ModelCallObservation::successful).count()).isEqualTo(1);
        server.verify();
    }

    @Test
    void planUsesFourthVersionInstructionAndOneCallWithoutFidelity() throws Exception {
        var planInput = new NaturalPromptInput("写邮件", input("17").context(), List.of(), List.of(), List.of(), null)
                .withStrategyVersion("17");
        var planTask = new NaturalTaskUnderstanding("写邮件", "邮件", List.of(goalRequirement()), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        String plan = json.writeValueAsString(Map.of("summary", "任务充分", "questions", List.of(), "taskUnderstanding", planTask));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptInstructions.planningInstruction("4"));
            assertThat(taskInput(request).has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response(plan), MediaType.APPLICATION_JSON));
        var result = provider.plan(planInput, value -> {
            value.taskUnderstanding().review().validatePlan(Set.of());
            return value;
        });
        assertThat(result.questions()).isEmpty();
        assertThat(json.writeValueAsString(result)).doesNotContain("fidelityDraft", "taskUnderstanding");
        server.verify();
    }

    /** 验证审阅辅助字段不能替代原始需求、绑定题答或服务端来源目录。 */
    private void assertBoundSources(JsonNode payload) {
        assertThat(payload.path("rawPrompt").asText()).isEqualTo("写邮件");
        assertThat(payload.path("actualAnswers")).hasSize(1);
        assertThat(payload.path("actualAnswers").get(0).path("answer").asText()).isEqualTo("发布时间还没定。");
        assertThat(payload.path("taskSources")).hasSize(2);
        var ids = new ArrayList<String>();
        payload.path("taskSources").forEach(source -> ids.add(source.path("id").asText()));
        assertThat(ids).containsExactly("rawPrompt", "actualAnswers.0");
    }

    /** 只解析发送给模型的业务JSON，不读取请求认证头。 */
    private JsonNode taskInput(org.springframework.http.client.ClientHttpRequest request) throws java.io.IOException {
        var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
        String user = sent.path("messages").get(1).path("content").asText();
        return json.readTree(user.substring(user.indexOf('{')));
    }

    /** 实际回答由既有服务端绑定入口提供，测试不得将模型建议作为用户答复。 */
    private NaturalPromptInput input(String version) {
        return new NaturalPromptInput("写邮件", new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(new PlanAnswer("q_time", "发布时间是什么？", "发布时间还没定。")), List.of(), List.of(), null)
                .withStrategyVersion(version);
    }

    /** 两项要求分别关联原始需求和仍未知的实际答复，最终覆盖只跟随正文变化。 */
    private NaturalTaskUnderstanding task(boolean preparation, String confirmationQuote) {
        return new NaturalTaskUnderstanding("写邮件", "邮件", List.of(goalRequirement(),
                new NaturalTaskUnderstanding.Requirement("发布时间以实际回答为准",
                        List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers.0", "发布时间还没定。")),
                        List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION))), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION,
                        preparation ? List.of() : List.of(new NaturalTaskReview.Coverage(0, List.of("写邮件")),
                                new NaturalTaskReview.Coverage(1, List.of(confirmationQuote))), List.of(), List.of()));
    }

    /** 保留明确的原始任务来源，供直接增强与零问Plan共同使用。 */
    private NaturalTaskUnderstanding.Requirement goalRequirement() {
        return new NaturalTaskUnderstanding.Requirement("写邮件",
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "写邮件")), List.of(NaturalTaskUnderstanding.Dimension.GOAL));
    }

    /** 首稿及补丁稿都按既有增强协议构造，错误事实也必须具有合法来源与正文引用才进入语义检查。 */
    private String draftResponse(String body, String confirmationQuote) throws Exception {
        return json.writeValueAsString(Map.of("optimizedPrompt", body, "framework", "APE", "taskUnderstanding", task(false, confirmationQuote)));
    }

    /** 无问题响应不提供替代稿或重新抽取任务，确保不会凭空改写已经有效的内容。 */
    private String noIssues() throws Exception {
        ObjectNode result = json.createObjectNode();
        result.putArray("issues");
        result.putNull("taskUnderstanding");
        return json.writeValueAsString(result);
    }

    /** 修补仅给真实来源及首稿中的唯一局部原句，重新填写最终正文的覆盖记录。 */
    private String patch(String sourceId, String sourceQuote, String bodyQuote, String replacement) throws Exception {
        return json.writeValueAsString(Map.of("issues", List.of(Map.of("type", "UNSUPPORTED_FACT",
                "sourceId", sourceId, "sourceQuote", sourceQuote, "bodyQuote", bodyQuote, "replacement", replacement)),
                "taskUnderstanding", task(false, "发布时间尚未确定")));
    }

    /** 每次模拟调用携带用量，包含最终拒绝的审阅，验证它们仍进入实际调用账目。 */
    private String response(String content) throws Exception {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }
}
