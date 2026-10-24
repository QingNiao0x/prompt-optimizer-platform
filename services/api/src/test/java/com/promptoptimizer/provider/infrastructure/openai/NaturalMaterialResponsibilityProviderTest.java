package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 验证真实Provider边界的责任传递与错误派发，不以固定模型夹具宣称自由文本语义已正确。 */
class NaturalMaterialResponsibilityProviderTest {
    private static final String ENDPOINT = "http://localhost:19000/v1/chat/completions";
    private static final String RAW = "根据执行时提供的记录写总结，先问我是否允许对外发布。";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;
    private final NaturalPromptInput input = new NaturalPromptInput(RAW, null, List.of(), List.of(), List.of(), null)
            .withStrategyVersion("35");

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var settings = new OpenAiCompatibleProperties();
        settings.setEndpoint(java.net.URI.create(ENDPOINT));
        settings.setApiKey("fixture");
        settings.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, settings,
                (com.promptoptimizer.provider.service.PlatformModelCatalog) null);
    }

    @Test
    void preparationIsPassedToWritingWithoutConvertingUnseenMaterialsToMissingFacts() throws Exception {
        expect(preparation(), false);
        expect(enhancement(), true);
        var draft = provider.enhance(input, value -> value);
        assertThat(draft.optimizedPrompt()).isEqualTo(RAW);
        assertThat(draft.taskUnderstanding().goal()).isEqualTo("根据记录写总结");
        assertThat(draft.taskUnderstanding().openDecisions()).hasSize(1);
        // 没有framework也能交付；APE仅为现有元数据兼容值，不表示模型使用了该框架。
        assertThat(draft.framework()).isNotNull();
        server.verify();
    }

    @Test
    void aRealUserDecisionCanStillBeAsked() throws Exception {
        expect(preparation(), false);
        expect(plan("permission"), true);
        var response = provider.plan(input, value -> value);
        assertThat(response.questions()).hasSize(1);
        assertThat(response.questions().getFirst().question()).isEqualTo("是否允许对外发布？");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"records", "unknown"})
    void executorWorkAndUnknownReferencesCannotBecomePlanQuestions(String dependency) throws Exception {
        expect(preparation(), false);
        expect(plan(dependency), true);
        expect(plan(dependency), true);
        assertThatThrownBy(() -> provider.plan(input, value -> value)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void executorWorkCannotBeReintroducedAsAnUnansweredDecisionInBody() throws Exception {
        expect(preparation(), false);
        var body = enhancement();
        ((ObjectNode) body.path("task").path("openDecisions").get(0)).put("dependencyId", "records");
        expect(body, true);
        expect(body, true);
        assertThatThrownBy(() -> provider.enhance(input, value -> value)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void laterStageCanCorrectAnExecutorClassificationUsingOriginalUserEvidence() throws Exception {
        var prep = preparation();
        ((ObjectNode) prep.path("dependencies").get(1)).put("owner", "EXECUTOR_WORK");
        expect(prep, false);
        var plan = plan("permission");
        attachCorrection(plan, "先问我是否允许对外发布");
        expect(plan, true);
        assertThat(provider.plan(input, value -> value).questions()).hasSize(1);
        server.verify();
    }

    @Test
    void laterStageCanIdentifyAnOmittedUserDecisionWithoutInventingASource() throws Exception {
        var prep = preparation();
        ((com.fasterxml.jackson.databind.node.ArrayNode) prep.path("dependencies")).remove(1);
        expect(prep, false);
        var plan = plan("permission");
        attachCorrection(plan, "先问我是否允许对外发布");
        expect(plan, true);
        assertThat(provider.plan(input, value -> value).questions()).hasSize(1);
        server.verify();
    }

    @Test
    void correctionCannotInventUserEvidenceToBypassResponsibilityChecks() throws Exception {
        expect(preparation(), false);
        var plan = plan("records");
        attachCorrection(plan, "用户确认必须先补齐全部数据");
        expect(plan, true);
        expect(plan, true);
        assertThatThrownBy(() -> provider.plan(input, value -> value)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    private void attachCorrection(ObjectNode plan, String quote) {
        var basis = (ObjectNode) plan.path("questions").get(0).path("decisionBasis").get(0);
        basis.putObject("correction").put("reason", "原文明确要求用户决定发布权限")
                .putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", quote);
    }

    @Test
    void anIdentifiedUserDecisionCannotDisappearInAnEmptyPlan() throws Exception {
        expect(preparation(), false);
        var empty = plan("permission");
        empty.putArray("questions");
        expect(empty, true);
        expect(empty, true);
        assertThatThrownBy(() -> provider.plan(input, value -> value)).isInstanceOf(ProviderException.class)
                .hasMessageContaining("用户决定未完整保留");
        server.verify();
    }

    @Test
    void noUserDecisionAllowsAnEmptyPlanWithoutAskingForExecutionMaterials() throws Exception {
        String raw = "请读取执行时提供的记录并生成总结。";
        var requestInput = new NaturalPromptInput(raw, null, List.of(), List.of(), List.of(), null).withStrategyVersion("35");
        var prep = preparation();
        ((com.fasterxml.jackson.databind.node.ArrayNode) prep.path("dependencies")).remove(1);
        for (var evidence : List.of(prep.path("taskScope").path("actions").get(0).path("evidence").get(0),
                prep.path("taskScope").path("response").path("evidence").get(0),
                prep.path("dependencies").get(0).path("evidence").get(0))) {
            ((ObjectNode) evidence).put("quote", raw);
        }
        expect(prep, false, requestInput);
        var plan = plan("permission");
        plan.putArray("questions");
        plan.put("summary", "根据执行时的记录生成总结。");
        var requirement = (ObjectNode) plan.path("task").path("requirements").get(0);
        requirement.put("text", raw);
        ((ObjectNode) requirement.path("evidence").get(0)).put("quote", raw);
        expect(plan, true, requestInput);
        assertThat(provider.plan(requestInput, value -> value).questions()).isEmpty();
        server.verify();
    }

    private ObjectNode preparation() throws Exception {
        return (ObjectNode) json.readTree("""
                {"taskScope":{"actions":[{"text":"根据记录写总结","timing":"CURRENT","condition":"","evidence":[{"sourceId":"rawPrompt","quote":"根据执行时提供的记录写总结"}]}],
                 "response":{"basis":"EXPLICIT","description":"总结","evidence":[{"sourceId":"rawPrompt","quote":"写总结"}]}},
                 "dependencies":[{"id":"records","subject":"执行记录","owner":"EXECUTOR_WORK","action":"执行时读取记录","reason":"据记录完成总结","evidence":[{"sourceId":"rawPrompt","quote":"根据执行时提供的记录"}]},
                  {"id":"permission","subject":"发布权限","owner":"USER_DECISION","action":"确认是否允许对外发布","reason":"决定可披露范围","evidence":[{"sourceId":"rawPrompt","quote":"先问我是否允许对外发布"}]}]}
                """);
    }

    private ObjectNode enhancement() throws Exception {
        return (ObjectNode) json.readTree("""
                {"optimizedPrompt":"根据执行时提供的记录写总结，先问我是否允许对外发布。","task":{"inputReadiness":"SUFFICIENT",
                 "requirements":[{"text":"完成总结并确认发布权限","dimensions":["GOAL","CONDITION"],"evidence":[{"sourceId":"rawPrompt","quote":"根据执行时提供的记录写总结，先问我是否允许对外发布。"}],
                   "bodyQuotes":["根据执行时提供的记录写总结，先问我是否允许对外发布。"]}],"additions":[],
                 "openDecisions":[{"dependencyId":"permission","issue":"发布权限","impact":"影响对外披露","kind":"USER_INFORMATION"}]}}
                """);
    }

    private ObjectNode plan(String dependency) throws Exception {
        var plan = (ObjectNode) json.readTree("""
                {"summary":"确认发布权限，记录由执行者读取。","task":{"inputReadiness":"SUFFICIENT",
                  "requirements":[{"text":"完成总结并确认发布权限","dimensions":["GOAL","CONDITION"],"evidence":[{"sourceId":"rawPrompt","quote":"根据执行时提供的记录写总结，先问我是否允许对外发布。"}]}],"unaskedDecisions":[]},
                 "questions":[{"id":"permission","question":"是否允许对外发布？","hint":"影响披露范围","type":"FREE_TEXT","options":[],"examples":[],"allowCustomAnswer":true,
                   "decisionBasis":[{"dependencyId":"permission","issue":"发布权限","kind":"USER_INFORMATION","whyAsk":"仅用户可以授权","answerDifference":"决定是否可对外披露"}]}]}
                """);
        ((ObjectNode) plan.path("questions").get(0).path("decisionBasis").get(0)).put("dependencyId", dependency);
        return plan;
    }

    /** 同一模拟传输检查原输入未损失、责任不是来源，正文/Plan确实接收到准备结果。 */
    private void expect(ObjectNode content, boolean prepared) throws Exception {
        expect(content, prepared, input);
    }

    private void expect(ObjectNode content, boolean prepared, NaturalPromptInput requestInput) throws Exception {
        var response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String message = sent.path("messages").get(1).path("content").asText();
            var payload = json.readTree(message.substring(message.indexOf('{')));
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(requestInput.rawPrompt());
            assertThat(payload.path("taskSources")).isEqualTo(json.valueToTree(requestInput.taskSources()));
            assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(requestInput.actualAnswers()));
            assertThat(payload.path("inputBoundary").path("stage").asText()).isEqualTo("PROMPT_OPTIMIZATION");
            assertThat(payload.has("executionResponsibilities")).isEqualTo(prepared);
            assertThat(payload.has("taskScope")).isEqualTo(prepared);
            if (prepared) assertThat(payload.path("executionResponsibilities").get(0).path("owner").asText()).isEqualTo("EXECUTOR_WORK");
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
