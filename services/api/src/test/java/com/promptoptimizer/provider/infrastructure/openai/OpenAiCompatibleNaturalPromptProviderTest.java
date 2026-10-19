package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.logging.*;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** HTTP协议、失败计量和有界修复使用本地Mock服务器，不访问外部服务。 */
class OpenAiCompatibleNaturalPromptProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
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
        properties.setModels(List.of("fixture"));
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }
    @Test
    void acceptsPlainCanonicalDocumentWithoutFourPartProtocol() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            var input = json.readTree(body.path("messages").get(1).path("content").asText().split("\n", 2)[1]);
            assertThat(input.has("template")).isFalse();
            assertThat(input.has("sections")).isFalse();
            assertThat(input.has("taskSources")).isFalse();
            assertThat(input.has("strategyVersion")).isFalse();
            assertThat(input.path("rawPrompt").asText()).isEqualTo("写邮件");
        }).andRespond(withSuccess(response("{\"optimizedPrompt\":\"完整自然正文\",\"framework\":\"APE\"}"), MediaType.APPLICATION_JSON));
        var result = provider.enhance(input(), Function.identity());
        assertThat(result.optimizedPrompt()).isEqualTo("完整自然正文");
        server.verify();
    }
    @Test
    void parsingFailureAndSuccessAreBothCountedWithActualUsage() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("invalid"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{\"optimizedPrompt\":\"正文\",\"framework\":\"ICIO\"}"), MediaType.APPLICATION_JSON));
        var facts = new ArrayList<ModelCallObservation>();
        try (var scope = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(new UUID(0, 1), (tenant, fact) -> facts.add(fact)))) {
            provider.enhance(input(), Function.identity());
        }
        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        assertThat(facts).allSatisfy(fact -> assertThat(fact.usage().totalTokens()).isEqualTo(15L));
        server.verify();
    }
    @Test
    void invalidResponsesStopAfterOneRepair() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{}"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input(), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }
    @Test
    void timeoutDoesNotConsumeARepairCall() {
        server.expect(requestTo(ENDPOINT)).andRespond(request -> { throw new java.net.SocketTimeoutException("offline"); });
        assertThatThrownBy(() -> provider.enhance(input(), Function.identity()))
                .isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.getFailureType()).isEqualTo(ProviderFailureType.TIMEOUT));
        server.verify();
    }
    @Test
    void authenticationFailureDoesNotRetry() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> provider.plan(input(), Function.identity()))
                .isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.getFailureType()).isEqualTo(ProviderFailureType.AUTHENTICATION));
        server.verify();
    }
    @Test
    void planCanReturnZeroQuestionsUsingItsOwnProtocol() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{\"summary\":\"任务已充分\",\"questions\":[]}"), MediaType.APPLICATION_JSON));
        assertThat(provider.plan(input(), Function.identity()).questions()).isEmpty();
        server.verify();
    }

    @Test
    void understandingReferencesAreResolvedFromServerSourcesAndKeptInternalToTheDraft() throws Exception {
        String task = "{\"goal\":\"写邮件\",\"deliverable\":\"邮件\",\"requirements\":[{\"text\":\"写邮件\","
                + "\"evidence\":[{\"sourceId\":\"rawPrompt\",\"quote\":\"写邮件\",\"origin\":\"BOUND_PLAN_ANSWER\"}]}]}";
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{\"optimizedPrompt\":\"完整邮件要求\",\"framework\":\"APE\","
                + "\"taskUnderstanding\":" + task + "}"), MediaType.APPLICATION_JSON));
        var result = provider.enhance(input(), Function.identity());
        assertThat(result.taskUnderstanding().requirements().getFirst().evidence().getFirst().origin())
                .isEqualTo(com.promptoptimizer.provider.domain.NaturalTaskSource.Kind.ORIGINAL_REQUEST);
        server.verify();
    }

    @Test
    void absentEvidenceConsumesTheExistingRepairBudgetAndNeverBecomesASuccessfulDraft() throws Exception {
        String content = "{\"optimizedPrompt\":\"邮件要求\",\"framework\":\"APE\",\"taskUnderstanding\":{\"goal\":\"写邮件\","
                + "\"requirements\":[{\"text\":\"虚构确认\",\"evidence\":[{\"sourceId\":\"actualAnswers.0\",\"quote\":\"用户已确认\"}]}]}}";
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input(), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void secondVersionUsesTaskDrivenGuidanceAndReturnsUnderstandingInTheSameCall() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("messages").get(0).path("content").asText()).contains("真正影响完成任务的缺口", "平台统一组装");
            var payload = json.readTree(body.path("messages").get(1).path("content").asText().split("\n", 2)[1]);
            assertThat(payload.path("taskSources").get(0).path("id").asText()).isEqualTo("rawPrompt");
        }).andRespond(withSuccess(response("{\"optimizedPrompt\":\"交付邮件正文，保留原定目的。\",\"framework\":\"APE\","
                + "\"taskUnderstanding\":{\"goal\":\"写邮件\",\"deliverable\":\"邮件正文\",\"requirements\":[],\"additions\":[],\"openDecisions\":[]}}"), MediaType.APPLICATION_JSON));
        assertThat(provider.enhance(input().withStrategyVersion("2"), Function.identity()).taskUnderstanding().goal()).isEqualTo("写邮件");
        server.verify();
    }

    @Test
    void secondVersionCannotAcceptAnOldResponseWithoutItsTaskRecord() throws Exception {
        String old = response("{\"optimizedPrompt\":\"邮件正文\",\"framework\":\"APE\"}");
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(old, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(old, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input().withStrategyVersion("2"), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void secondVersionPlanSharesTheSourceProtocolAndCanKeepZeroQuestions() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("messages").get(0).path("content").asText()).contains("用户未委托执行者合理设计", "交付影响");
        }).andRespond(withSuccess(response("{\"summary\":\"任务已有明确目标，直接交付邮件\",\"questions\":[],"
                + "\"taskUnderstanding\":{\"goal\":\"写邮件\",\"requirements\":[],\"additions\":[],\"openDecisions\":[]}}"), MediaType.APPLICATION_JSON));
        var result = provider.plan(input().withStrategyVersion("2"), Function.identity());
        assertThat(result.questions()).isEmpty();
        assertThat(result.taskUnderstanding().goal()).isEqualTo("写邮件");
        assertThat(json.writeValueAsString(result)).doesNotContain("taskUnderstanding");
        server.verify();
    }
    @Test
    void thirdVersionUsesDeliverableGuidanceWithTheSameSourceProtocol() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("messages").get(0).path("content").asText()).contains("目标作品", "已提供的具体信息");
            var payload = json.readTree(body.path("messages").get(1).path("content").asText().split("\n", 2)[1]);
            assertThat(payload.path("strategyVersion").asText()).isEqualTo("3");
            assertThat(payload.has("template")).isFalse();
            assertThat(payload.path("taskSources").get(0).path("id").asText()).isEqualTo("rawPrompt");
        }).andRespond(withSuccess(response("{\"optimizedPrompt\":\"写一封符合原意的邮件。\",\"framework\":\"APE\","
                + "\"taskUnderstanding\":{\"goal\":\"写邮件\",\"deliverable\":\"邮件\",\"requirements\":[],\"additions\":[],\"openDecisions\":[]}}"),
                MediaType.APPLICATION_JSON));
        assertThat(provider.enhance(input().withStrategyVersion("3"), Function.identity()).optimizedPrompt()).contains("邮件");
        server.verify();
    }

    @Test
    void thirdVersionPlanUsesUserOwnedDecisionsAndKeepsItsTaskRecord() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("messages").get(0).path("content").asText()).contains("重要决定确实属于用户");
        }).andRespond(withSuccess(response("{\"summary\":\"原定任务可以执行\",\"questions\":[],"
                + "\"taskUnderstanding\":{\"goal\":\"写邮件\",\"requirements\":[],\"additions\":[],\"openDecisions\":[]}}"),
                MediaType.APPLICATION_JSON));
        assertThat(provider.plan(input().withStrategyVersion("3"), Function.identity()).questions()).isEmpty();
        server.verify();
    }

    @Test
    void thirdVersionCannotDropTheExistingTaskRecordProtection() throws Exception {
        String old = response("{\"optimizedPrompt\":\"邮件正文\",\"framework\":\"APE\"}");
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(old, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(old, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input().withStrategyVersion("3"), Function.identity()))
                .isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void fourthVersionReturnsCoverageInTheSameCallWithoutDefaultRulesOrExtraReviewer() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("messages").get(0).path("content").asText()).contains("返回前对照", "requirementCoverage", "缺口");
            var payload = json.readTree(body.path("messages").get(1).path("content").asText().split("\n", 2)[1]);
            assertThat(payload.path("strategyVersion").asText()).isEqualTo("4");
            assertThat(payload.path("mandatoryRules").isEmpty()).isTrue();
        }).andRespond(withSuccess(response(reviewResponse("请写邮件。", false)), MediaType.APPLICATION_JSON));
        var result = provider.enhance(input().withStrategyVersion("4"), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo("请写邮件。");
        server.verify();
    }

    @Test
    void fourthVersionDoesNotAcceptAnOlderTaskRecordWithoutCoverage() throws Exception {
        String old = response("{\"optimizedPrompt\":\"写邮件\",\"framework\":\"APE\",\"taskUnderstanding\":{\"goal\":\"写邮件\"}}");
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(old, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(old, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input().withStrategyVersion("4"), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void absentBodyEvidenceUsesOnlyTheExistingBoundedProtocolRepair() throws Exception {
        String wrong = response(reviewResponse("正文并未包含声明中的目标片段。", false));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(wrong, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(wrong, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input().withStrategyVersion("4"), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        })).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void fourthVersionPlanKeepsZeroQuestionsAndItsInternalReviewOffTheWire() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(reviewResponse("", true)), MediaType.APPLICATION_JSON));
        var result = provider.plan(input().withStrategyVersion("4"), draft -> {
            draft.taskUnderstanding().review().validatePlan(Set.of());
            return draft;
        });
        assertThat(result.questions()).isEmpty();
        assertThat(json.writeValueAsString(result)).doesNotContain("taskUnderstanding", "requirementCoverage");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 8, 12, 13})
    void replaysActualMissingRootClosureWithoutChangingAnyContentValues(int traceIndex) throws Exception {
        var lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(
                "../../docs/testing/evidence/natural-coverage-workflow-2026-10-20/transports-candidate1.jsonl"));
        var trace = json.readTree(lines.get(traceIndex - 1));
        String content = trace.path("response").path("choices").get(0).path("message").path("content").asText();
        String wire = trace.path("request").path("messages").get(1).path("content").asText();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(wire.substring(wire.indexOf('{')));
        payload.remove("taskSources");
        payload.putNull("modelId");
        json.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var actualInput = json.treeToValue(payload, NaturalPromptInput.class);
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(json.writeValueAsString(trace.get("response")), MediaType.APPLICATION_JSON));
        var result = provider.enhance(actualInput, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(json.readTree(content + "}").path("optimizedPrompt").asText());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"optimizedPrompt\":\"正文", "{\"optimizedPrompt\":123", "{\"outer\":{\"nested\":[]", "{\"optimizedPrompt\":\"正文\",,}"})
    void doesNotGuessStringsScalarsArraysMultipleClosuresOrOtherSyntax(String content) throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input(), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void boundedRepairNamesMissingSourcesWithoutMergingSameTextAcrossDifferentQuestions() throws Exception {
        var answers = List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer("q1", "甲对象的称呼？", "使用内部称呼"),
                new com.promptoptimizer.enhancement.dto.PlanAnswer("q2", "乙对象的称呼？", "使用内部称呼"),
                new com.promptoptimizer.enhancement.dto.PlanAnswer("q3", "丙对象的称呼？", "使用内部称呼"));
        var actualInput = new NaturalPromptInput("写邮件", input().context(), answers, List.of(), false, List.of(), null);
        String partial = sourceResponse(1);
        String complete = sourceResponse(3);
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(partial), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var payload = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(payload.path("messages").get(0).path("content").asText())
                    .contains("actualAnswers.1", "actualAnswers.2").doesNotContain("使用内部称呼", "甲对象", "乙对象");
        }).andRespond(withSuccess(response(complete), MediaType.APPLICATION_JSON));
        var result = provider.enhance(actualInput, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.taskUnderstanding().requirements()).hasSize(4);
        assertThat(actualInput.actualAnswers()).isEqualTo(answers);
        server.verify();
    }

    private String sourceResponse(int answerSources) throws Exception {
        var requirements = new ArrayList<NaturalTaskUnderstanding.Requirement>();
        var coverage = new ArrayList<NaturalTaskReview.Coverage>();
        requirements.add(new NaturalTaskUnderstanding.Requirement("写邮件", List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "写邮件")),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL)));
        coverage.add(new NaturalTaskReview.Coverage(0, List.of("写邮件")));
        for (int i = 0; i < answerSources; i++) {
            requirements.add(new NaturalTaskUnderstanding.Requirement("保留对象" + i + "的实际回答",
                    List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers." + i, "使用内部称呼")),
                    List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION)));
            coverage.add(new NaturalTaskReview.Coverage(i + 1, List.of("使用内部称呼")));
        }
        var task = new NaturalTaskUnderstanding("写邮件", "邮件", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, coverage, List.of(), List.of()));
        return json.writeValueAsString(Map.of("optimizedPrompt", "请写邮件，使用内部称呼。", "framework", "APE", "taskUnderstanding", task));
    }

    private String reviewResponse(String body, boolean planning) throws Exception {
        var requirement = new NaturalTaskUnderstanding.Requirement("写邮件", List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "写邮件")),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL));
        var coverage = planning ? List.<NaturalTaskReview.Coverage>of() : List.of(new NaturalTaskReview.Coverage(0, List.of("写邮件")));
        var task = new NaturalTaskUnderstanding("写邮件", "邮件", List.of(requirement), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, coverage, List.of(), List.of()));
        return json.writeValueAsString(planning ? Map.of("summary", "任务充分，继续交付", "questions", List.of(), "taskUnderstanding", task)
                : Map.of("optimizedPrompt", body, "framework", "APE", "taskUnderstanding", task));
    }

    private NaturalPromptInput input() {
        return new NaturalPromptInput("写邮件", new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(), List.of(), false, List.of(), null).withStrategyVersion("1");
    }
    private String response(String content) throws Exception {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", content))), "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }
}
