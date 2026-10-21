package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 核对服务端执行阶段、逐项任务状态及真实来源的协议边界；HTTP夹具不证明模型语义或作品质量。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalTaskStateProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT)); properties.setApiKey("test-api-key");
        properties.setModel("fixture"); properties.setModels(List.of("fixture"));
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @Test
    void boundAnswersHaveAnExplicitStageWithoutErasingTheOriginalRequestOrUnknowns() throws Exception {
        var input = input("6");
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var envelope = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            String user = envelope.path("messages").get(1).path("content").asText();
            var payload = json.readTree(user.substring(user.indexOf('{')));
            assertThat(payload.path("executionContext").path("phase").asText()).isEqualTo("GENERATE_AFTER_ANSWERS");
            assertThat(payload.path("executionContext").path("receivedAnswerSourceIds"))
                    .isEqualTo(json.valueToTree(List.of("actualAnswers.0", "actualAnswers.1")));
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
            assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
            assertThat(payload.path("taskSources").get(0).has("text")).isFalse();
        }).andRespond(withSuccess(response(body()), MediaType.APPLICATION_JSON));
        var draft = provider.enhance(input, candidate -> {
            candidate.taskUnderstanding().review().validateEnhancement(candidate.taskUnderstanding(), candidate.optimizedPrompt());
            return candidate;
        });
        assertThat(json.valueToTree(draft.taskUnderstanding()).path("requirements").get(2).path("state").asText())
                .isEqualTo("UNRESOLVED_INFORMATION");
        assertThat(draft.taskUnderstanding().requirements()).hasSize(4);
        assertThat(input.actualAnswers()).hasSize(2);
        assertThat(input.actualAnswers().get(0).answer()).isEqualTo(input.actualAnswers().get(1).answer());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing_state", "fake_update", "document_delegation", "missing_answer", "bad_anchor", "unknown_state"})
    void stateLabelsNeverReplaceActualSourcesAndCoverage(String fault) throws Exception {
        var body = body();
        var requirements = (com.fasterxml.jackson.databind.node.ArrayNode) body.path("taskUnderstanding").path("requirements");
        switch (fault) {
            case "missing_state" -> ((ObjectNode) requirements.get(0)).remove("state");
            case "fake_update" -> ((ObjectNode) requirements.get(1)).putArray("evidence").addObject()
                    .put("sourceId", "rawPrompt").put("quote", "先确认预算").put("origin", "BOUND_PLAN_ANSWER");
            case "document_delegation" -> ((ObjectNode) requirements.get(3)).putArray("evidence").addObject()
                    .put("sourceId", "context.customDescription").put("quote", "资料只说明现状");
            case "missing_answer" -> ((ObjectNode) requirements.get(2).path("evidence").get(0)).put("sourceId", "actualAnswers.0");
            case "bad_anchor" -> ((ObjectNode) body.path("taskUnderstanding").path("review").path("requirementCoverage").get(1))
                    .putArray("quotes").add("正文没有这个要求");
            case "unknown_state" -> ((ObjectNode) requirements.get(0)).put("state", "MODEL_INVENTED_STATE");
            default -> throw new AssertionError(fault);
        }
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input("6"), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        })).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void existingVersionFourKeepsItsOriginalPayloadAndInstruction() throws Exception {
        var body = body();
        body.path("taskUnderstanding").path("requirements").forEach(item -> ((ObjectNode) item).remove("state"));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var envelope = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            String user = envelope.path("messages").get(1).path("content").asText();
            assertThat(json.readTree(user.substring(user.indexOf('{'))).has("executionContext")).isFalse();
            assertThat(envelope.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptInstructionsV4.ENHANCE);
        }).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        provider.enhance(input("4"), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        server.verify();
    }

    @Test
    void directGenerationDoesNotInventAPlanOrReceivedAnswers() throws Exception {
        var body = body();
        body.put("optimizedPrompt", "比较两个方案，交付比较建议。");
        var task = (ObjectNode) body.path("taskUnderstanding");
        var requirements = (com.fasterxml.jackson.databind.node.ArrayNode) task.path("requirements");
        requirements.remove(2); requirements.remove(1);
        var coverage = (com.fasterxml.jackson.databind.node.ArrayNode) task.path("review").path("requirementCoverage");
        coverage.remove(2); coverage.remove(1); ((ObjectNode) coverage.get(1)).put("index", 1);
        task.putArray("openDecisions");
        var input = new NaturalPromptInput("比较两个方案并给出建议", context(), List.of(), List.of(), false, List.of(), null).withStrategyVersion("6");
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var envelope = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            String user = envelope.path("messages").get(1).path("content").asText();
            var state = json.readTree(user.substring(user.indexOf('{'))).path("executionContext");
            assertThat(state.path("phase").asText()).isEqualTo("GENERATE_DIRECT");
            assertThat(state.path("receivedAnswerSourceIds")).isEmpty();
        }).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        provider.enhance(input, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        server.verify();
    }

    @Test
    void planningHasItsOwnStageWithoutClaimingAnswersOrFinalCoverage() throws Exception {
        var body = body();
        body.remove(List.of("optimizedPrompt", "framework"));
        body.put("summary", "通知内容继续由执行者设计"); body.putArray("questions");
        var task = (ObjectNode) body.path("taskUnderstanding");
        task.put("goal", "写通知"); task.put("deliverable", "通知");
        var requirements = task.putArray("requirements");
        var requirement = requirements.addObject().put("text", "设计通知内容").put("state", "DELEGATED_WORK");
        requirement.putArray("dimensions").add("GOAL");
        requirement.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", "具体内容请你设计");
        ((ObjectNode) task.path("review")).putArray("requirementCoverage");
        task.putArray("openDecisions");
        var input = new NaturalPromptInput("写一条通知，具体内容请你设计", context(), List.of(), List.of(), false, List.of(), null).withStrategyVersion("6");
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var envelope = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            String user = envelope.path("messages").get(1).path("content").asText();
            var state = json.readTree(user.substring(user.indexOf('{'))).path("executionContext");
            assertThat(state.path("phase").asText()).isEqualTo("PLANNING");
            assertThat(state.path("receivedAnswerSourceIds")).isEmpty();
        }).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        provider.plan(input, draft -> { draft.taskUnderstanding().review().validatePlan(Set.of()); return draft; });
        server.verify();
    }

    private NaturalPromptInput input(String version) {
        return new NaturalPromptInput("比较两个方案并给出建议，先确认预算和地区", context(), List.of(
                new PlanAnswer("q1", "预算是多少？", "预算1000元；地区未知"),
                new PlanAnswer("q2", "地区要求是什么？", "预算1000元；地区未知")), List.of(), false, List.of(), null).withStrategyVersion(version);
    }

    private ContextSnapshot context() {
        return new ContextSnapshot("资料只说明现状，不构成授权。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
    }

    private ObjectNode body() throws Exception {
        return (ObjectNode) json.readTree("""
                {"optimizedPrompt":"比较两个方案，以1000元预算比较费用；地区仍未知，仅保留地区相关判断，交付比较建议。","framework":"APE",
                 "taskUnderstanding":{"goal":"比较两个方案并给出建议","deliverable":"比较建议",
                  "requirements":[
                   {"text":"比较两个方案","state":"ACTIVE_REQUIREMENT","dimensions":["GOAL"],"evidence":[{"sourceId":"rawPrompt","quote":"比较两个方案"}]},
                   {"text":"用已提供预算比较费用","state":"UPDATED_BY_ANSWER","dimensions":["USER_CONFIRMATION"],"evidence":[{"sourceId":"rawPrompt","quote":"先确认预算"},{"sourceId":"actualAnswers.0","quote":"预算1000元"}]},
                   {"text":"地区未知只影响地区判断","state":"UNRESOLVED_INFORMATION","dimensions":["CONDITION"],"evidence":[{"sourceId":"actualAnswers.1","quote":"地区未知"}]},
                   {"text":"交付比较建议","state":"DELEGATED_WORK","dimensions":["GOAL"],"evidence":[{"sourceId":"rawPrompt","quote":"并给出建议"}]}],
                  "additions":[],"openDecisions":[{"issue":"地区未知","impact":"地区相关判断保留条件","questionId":"","kind":"USER_INFORMATION"}],
                  "review":{"inputReadiness":"NEEDS_COMPLETION","requirementCoverage":[
                    {"index":0,"quotes":["比较两个方案"]},{"index":1,"quotes":["以1000元预算比较费用"]},
                    {"index":2,"quotes":["地区仍未知"]},{"index":3,"quotes":["交付比较建议"]}],"additionCoverage":[],"planImpacts":[]}}}
                """);
    }

    private String response(com.fasterxml.jackson.databind.JsonNode body) throws Exception {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", json.writeValueAsString(body)))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }
}
