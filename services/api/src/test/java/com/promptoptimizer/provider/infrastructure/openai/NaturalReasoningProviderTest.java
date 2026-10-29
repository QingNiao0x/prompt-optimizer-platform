package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.*;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 只验证推理候选的传输、旧版隔离及保护接线，不把合成响应当作模型质量证据。 */
class NaturalReasoningProviderTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final URI endpoint = URI.create("https://provider.invalid/chat/completions");

    @Test
    void enhancementUsesDedicatedTransportAndPreservesFullInput() throws Exception {
        var ordinary = RestClient.builder(); var thinking = RestClient.builder();
        var ordinaryServer = MockRestServiceServer.bindTo(ordinary).build();
        var thinkingServer = MockRestServiceServer.bindTo(thinking).build();
        var input = input("55", true); var requests = new ArrayList<JsonNode>();
        expect(thinkingServer, response(input, true), requests);
        var provider = new OpenAiCompatibleNaturalPromptProvider(ordinary.build(), thinking.build(), json, properties("deepseek"), (PlatformModelCatalog) null);
        var draft = provider.enhance(input, value -> value);
        assertThat(draft.completedStages()).containsExactly(com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.BODY_GENERATION);
        assertThat(requests).hasSize(1); var request = requests.get(0);
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(request.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(32768);
        String user = request.path("messages").get(1).path("content").asText();
        var actual = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(user.substring(user.indexOf('{')));
        assertThat(actual.path("inputBoundary").path("stage").asText()).isEqualTo("PROMPT_OPTIMIZATION");
        actual.remove("inputBoundary"); assertThat(actual).isEqualTo(json.valueToTree(input));
        assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptSinglePassInstructions.ENHANCE);
        ordinaryServer.verify(); thinkingServer.verify();
    }

    @Test
    void oldSinglePassStillUsesOrdinaryTransportAndOriginalParameters() throws Exception {
        var ordinary = RestClient.builder(); var thinking = RestClient.builder();
        var ordinaryServer = MockRestServiceServer.bindTo(ordinary).build();
        var thinkingServer = MockRestServiceServer.bindTo(thinking).build();
        var input = input("54", false); var requests = new ArrayList<JsonNode>();
        expect(ordinaryServer, response(input, true), requests);
        new OpenAiCompatibleNaturalPromptProvider(ordinary.build(), thinking.build(), json, properties("deepseek"), (PlatformModelCatalog) null)
                .enhance(input, value -> value);
        assertThat(requests.get(0).path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(requests.get(0).path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(requests.get(0).has("reasoning_effort")).isFalse(); ordinaryServer.verify(); thinkingServer.verify();
    }

    @Test
    void unadaptedRouteDoesNotReceiveVendorSpecificThinkingFields() throws Exception {
        var ordinary = RestClient.builder(); var thinking = RestClient.builder();
        var ordinaryServer = MockRestServiceServer.bindTo(ordinary).build();
        var thinkingServer = MockRestServiceServer.bindTo(thinking).build();
        var input = input("55", false); var requests = new ArrayList<JsonNode>();
        expect(ordinaryServer, response(input, true), requests);
        new OpenAiCompatibleNaturalPromptProvider(ordinary.build(), thinking.build(), json, properties("other"), (PlatformModelCatalog) null)
                .enhance(input, value -> value);
        assertThat(requests.get(0).has("thinking") || requests.get(0).has("reasoning_effort")).isFalse();
        assertThat(requests.get(0).path("max_tokens").asInt()).isEqualTo(8192);
        ordinaryServer.verify(); thinkingServer.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"55", "57", "58", "60"})
    void missingActualAnswerStillFailsAfterOneProtocolRepair(String version) throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var input = input(version, true); var requests = new ArrayList<JsonNode>();
        expect(server, response(input, false), requests); expect(server, response(input, false), requests);
        var provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties("deepseek"), (PlatformModelCatalog) null);
        assertThatThrownBy(() -> provider.enhance(input, value -> value)).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request -> assertThat(request.path("thinking").path("type").asText()).isEqualTo("enabled"));
        server.verify();
    }

    @Test
    void candidateKeepsFrozenWritingAndPlanInstructions() {
        assertThat(NaturalPromptInstructions.enhancementInstruction("55")).isEqualTo(NaturalPromptInstructions.enhancementInstruction("54"));
        assertThat(NaturalPromptInstructions.planningInstruction("55")).isEqualTo(NaturalPromptInstructions.planningInstruction("52"));
        assertThat(NaturalPromptStageInstructions.acceptsDiagnosticFields("55")).isTrue();
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"58", "60"})
    void objectReferencesAreNotInventedPermissionsAndAllUserSourcesRemainUnchanged(String version) throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var input = input(version, true); var requests = new ArrayList<JsonNode>();
        expect(server, response(input, true), requests);
        new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties("deepseek"), (PlatformModelCatalog) null)
                .enhance(input, value -> value);
        String text = requests.get(0).path("messages").path(1).path("content").asText();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(text.substring(text.indexOf('{')));
        assertThat(payload.path("inputBoundary").path("referenceRoles")).isEqualTo(json.valueToTree(NaturalPromptSinglePassInstructions.REFERENCE_ROLES));
        payload.remove("inputBoundary"); assertThat(payload).isEqualTo(json.valueToTree(input));
        assertThat(requests.get(0).path("messages").path(0).path("content").asText()).isEqualTo(NaturalPromptInstructions.enhancementInstruction(version));
        assertThat(NaturalPromptInstructions.planningInstruction("58")).isEqualTo(NaturalPromptInstructions.planningInstruction("57"));
        server.verify();
    }

    @Test
    void designResponsibilityChangesOnlyOneFrozenClauseWithoutAddingTemplatesOrCalls() {
        String old = "用户委托设计时给出适用的拟议设计，实际事实和用户保留的选择继续按原状态处理。";
        String replacement = "用户委托设计时，把委托整理成下游应完成的设计内容和适用条件；若提示做法，保持为可选建议，不替执行者预先锁定方案或新增禁止。实际事实和用户明确保留的选择继续按原状态处理。";
        assertThat(NaturalPromptInstructions.enhancementInstruction("60")).isEqualTo(NaturalPromptInstructions.enhancementInstruction("58").replace(old, replacement));
        assertThat(NaturalPromptInstructions.enhancementInstruction("58")).contains(old);
        assertThat(NaturalPromptInstructions.planningInstruction("60")).isEqualTo(NaturalPromptInstructions.planningInstruction("58"));
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
    }

    @Test
    void goalRoleCandidateOnlyAddsInternalClassificationInstructions() throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var input = input("56", false); var requests = new ArrayList<JsonNode>();
        expect(server, response(input, true), requests);
        new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties("deepseek"), (PlatformModelCatalog) null)
                .enhance(input, value -> value);
        String instruction = requests.get(0).path("messages").get(0).path("content").asText();
        assertThat(instruction).startsWith(NaturalPromptSinglePassInstructions.ENHANCE).contains("dimensions.GOAL只标记本次用户委托的任务目标");
        assertThat(requests.get(0).path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(requests.get(0).path("max_tokens").asInt()).isEqualTo(32768);
        assertThat(NaturalPromptInstructions.planningInstruction("56")).isEqualTo(NaturalPromptInstructions.planningInstruction("55"));
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"55", "59", "60", "61", "62", "63"})
    void planningUsesOriginalFastTransportAndCanonicalProtocol(String version) throws Exception {
        var ordinary = RestClient.builder(); var thinking = RestClient.builder();
        var ordinaryServer = MockRestServiceServer.bindTo(ordinary).build();
        var thinkingServer = MockRestServiceServer.bindTo(thinking).build();
        var input = input(version, false); var requests = new ArrayList<JsonNode>();
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
        expect(ordinaryServer, Map.of("actions", List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "", "evidence", evidence)),
                "response", Map.of("basis", "EXPLICIT", "description", "整理方案", "evidence", evidence)), requests);
        var required = new NaturalTaskUnderstanding.Requirement(input.rawPrompt(), List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", input.rawPrompt())),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL));
        var task = new NaturalTaskUnderstanding(input.rawPrompt(), "方案", List.of(required), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        expect(ordinaryServer, Map.of("summary", "按原需求整理方案，无须追问", "questions", List.of(), "taskUnderstanding", task), requests);
        var result = new OpenAiCompatibleNaturalPromptProvider(ordinary.build(), thinking.build(), json, properties("deepseek"), (PlatformModelCatalog) null).plan(input, value -> value);
        assertThat(result.questions()).isEmpty(); assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(request.path("max_tokens").asInt()).isEqualTo(8192);
            assertThat(request.has("reasoning_effort")).isFalse();
        });
        assertThat(requests.get(1).path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptSinglePassInstructions.PLAN);
        ordinaryServer.verify(); thinkingServer.verify();
    }

    @Test
    void explicitModelCapacityStillLimitsReasoningOutput() throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var input = input("55", false); var requests = new ArrayList<JsonNode>();
        expect(server, response(input, true), requests); var properties = properties("deepseek");
        properties.setCapacityLimits(Map.of("deepseek:deepseek-flash", new RequestCapacityPolicy.Limit(100_000, 16_384, 1000, "fixture", "2026-10-28")));
        new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null).enhance(input, value -> value);
        assertThat(requests.get(0).path("max_tokens").asInt()).isEqualTo(16_384); server.verify();
    }

    @Test
    void dedicatedTimeoutClientRetainsTheSharedInterceptors() throws Exception {
        var calls = new AtomicInteger(); var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); }); server.start();
        try {
            var shared = RestClient.builder().requestInterceptor((request, bytes, execution) -> { calls.incrementAndGet(); return execution.execute(request, bytes); }).build();
            var transport = new OpenAiCompatibleProviderConfiguration().naturalReasoningTransport(shared, properties("deepseek"));
            transport.client().get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/").retrieve().toBodilessEntity();
            shared.get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/").retrieve().toBodilessEntity();
            assertThat(calls.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    private OpenAiCompatibleProperties properties(String routeKey) {
        var route = new OpenAiCompatibleRouteProperties(); route.setProviderName(routeKey); route.setEndpoint(endpoint);
        route.setApiKey("fixture"); route.setModel("deepseek-flash");
        var value = new OpenAiCompatibleProperties(); value.setMultiProviderEnabled(true); value.setDefaultProvider(routeKey);
        value.setProviders(Map.of(routeKey, route)); value.setMaxTokens(8192); return value;
    }

    private NaturalPromptInput input(String version, boolean answered) {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), "COMPLETE", List.of(), List.of(), List.of(), "v3");
        return new NaturalPromptInput("请整理方案，不修改文件。", context,
                answered ? List.of(new PlanAnswer("budget", "预算？", "未知")) : List.of(), List.of(), List.of(), null).withStrategyVersion(version);
    }

    private Object response(NaturalPromptInput input, boolean includeAnswer) {
        var requirements = new ArrayList<Map<String, Object>>();
        for (var source : input.taskSources()) {
            if (!includeAnswer && source.id().startsWith("actualAnswers.")) continue;
            requirements.add(Map.of("text", source.text(), "dimensions", List.of(source.id().equals("rawPrompt") ? "GOAL" : "USER_CONFIRMATION"),
                    "evidence", List.of(Map.of("sourceId", source.id(), "quote", source.text())), "bodyQuotes", List.of("请整理方案，不修改文件；预算未知。")));
        }
        return Map.of("optimizedPrompt", "请整理方案，不修改文件；预算未知。", "framework", "APE", "task", Map.of(
                "goal", input.rawPrompt(), "deliverable", "方案", "inputReadiness", "SUFFICIENT", "requirements", requirements,
                "additions", List.of(), "openDecisions", List.of()));
    }

    private void expect(MockRestServiceServer server, Object body, List<JsonNode> requests) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", json.writeValueAsString(body))))));
        server.expect(requestTo(endpoint)).andExpect(request -> requests.add(json.readTree(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8))))
                .andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
