package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 正常入口验证诊断减负、首稿固定及来源与正文覆盖的等效保护。 */
class NaturalBalancedProviderTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String RAW = "请解释缓存失效，保留例外条件。";
    private static final String URL = "https://fixture.invalid/chat/completions";

    @Test
    void optionalDiagnosticsDoNotBlockAndScopeStillGenerates() throws Exception {
        var candidate = body(); candidate.put("framework", "UNKNOWN");
        ((ObjectNode) candidate.get("task")).put("inputReadiness", "UNKNOWN");
        assertThat(run(candidate, null).optimizedPrompt()).isEqualTo(RAW);
    }

    @Test
    void recordRepairKeepsBodyAndRechecksCoverage() throws Exception {
        var candidate = body();
        ((ObjectNode) candidate.at("/task/requirements/0/evidence/0")).put("quote", "不存在的引文");
        var patch = json.createObjectNode();
        patch.putArray("changes").addObject().put("path", "/task/requirements/0/evidence")
                .set("value", json.valueToTree(List.of(Map.of("sourceId", "rawPrompt", "quote", RAW))));
        assertThat(run(candidate, patch).optimizedPrompt()).isEqualTo(RAW);
        ((ObjectNode) candidate.at("/task/requirements/0")).putArray("bodyQuotes").add("正文中没有这句话");
        assertThatThrownBy(() -> run(candidate, patch)).isInstanceOf(ProviderException.class);
    }

    @Test
    void patchCannotRewriteBodyOrInventEvidence() {
        var candidate = body();
        ((ObjectNode) candidate.at("/task/requirements/0/evidence/0")).put("quote", "错引");
        var patch = json.createObjectNode();
        patch.putArray("changes").addObject().put("path", "/optimizedPrompt").put("value", "另一份正文");
        assertThatThrownBy(() -> run(candidate, patch)).isInstanceOf(ProviderException.class);
    }

    @Test
    void diagnosticObjectsStillFailAndOriginalIsNeverMutated() {
        var candidate = body(); candidate.putObject("framework").put("origin", "USER");
        assertThatThrownBy(() -> NaturalPromptDiagnosticProjection.normalize(candidate)).isInstanceOf(ProviderException.class);
        candidate.remove("framework");
        var projected = NaturalPromptDiagnosticProjection.normalize(candidate);
        assertThat(candidate.at("/task/inputReadiness").isMissingNode()).isTrue();
        assertThat(projected.at("/task/inputReadiness").asText()).isEqualTo("NOT_ASSESSED");
    }

    @Test
    void planProtocolAndUnknownDiagnosticPreserveZeroQuestionPath() throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", RAW));
        expect(server, json.valueToTree(Map.of("actions", List.of(Map.of("text", RAW, "timing", "CURRENT", "condition", "", "userEvidence", evidence, "detailEvidence", List.of())),
                "response", Map.of("basis", "INHERENT", "description", "解释", "userEvidence", evidence, "detailEvidence", List.of()))));
        var response = json.createObjectNode().put("summary", "解释缓存失效");
        var task = response.putObject("task").put("goal", RAW).put("deliverable", "解释").put("inputReadiness", "UNKNOWN");
        var requirement = task.putArray("requirements").addObject().put("text", RAW);
        requirement.putArray("dimensions").add("GOAL"); requirement.set("evidence", json.valueToTree(evidence));
        task.putArray("unaskedDecisions"); response.putArray("questions"); expect(server, response);
        var properties = new OpenAiCompatibleProperties(); properties.setEndpoint(URI.create(URL)); properties.setApiKey("fixture"); properties.setModel("fixture");
        var input = new NaturalPromptInput(RAW, new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "fixture"),
                List.of(), List.of(), List.of(), null).withStrategyVersion(NaturalPromptBalancedInstructions.VERSION);
        var result = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog)null).plan(input, value -> value);
        assertThat(result.questions()).isEmpty(); server.verify();
    }

    @Test
    void historicalMetadataFailuresReplayThroughCandidateWithoutChangingFirstDraft() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (var stream = getClass().getResourceAsStream("/natural-metadata-repair/real-first-drafts-and-patches.json")) {
            for (var fixture : mapper.readTree(stream)) {
                if (!Set.of("U02", "V02").contains(fixture.path("id").asText())) continue;
                String user = fixture.at("/request/messages/1/content").asText();
                ObjectNode data = (ObjectNode)mapper.readTree(user.substring(user.indexOf('{')));
                data.remove(List.of("taskSources", "inputBoundary"));
                data.putNull("modelId"); // 历史模型身份不决定本机受控传输的模拟端点。
                var input = mapper.treeToValue(data, NaturalPromptInput.class).withStrategyVersion(NaturalPromptBalancedInstructions.VERSION);
                var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
                var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
                expect(server, mapper.valueToTree(Map.of("actions", List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "", "userEvidence", evidence, "detailEvidence", List.of())),
                        "response", Map.of("basis", "INHERENT", "description", "原定结果", "userEvidence", evidence, "detailEvidence", List.of()))));
                String first = fixture.at("/firstResponse/choices/0/message/content").asText();
                expect(server, mapper.readTree(first));
                expect(server, mapper.readTree(fixture.at("/patchResponse/choices/0/message/content").asText()));
                var properties = new OpenAiCompatibleProperties(); properties.setEndpoint(URI.create(URL)); properties.setApiKey("fixture"); properties.setModel("fixture");
                var result = new OpenAiCompatibleNaturalPromptProvider(builder.build(), mapper, properties, (PlatformModelCatalog)null)
                        .enhance(input, value -> {value.taskUnderstanding().resolve(input).review().validateEnhancement(value.taskUnderstanding(), value.optimizedPrompt()); return value;});
                assertThat(result.optimizedPrompt()).isEqualTo(mapper.readTree(first).path("optimizedPrompt").asText());
                server.verify();
            }
        }
    }

    @Test
    void frozenRealCandidateResponsesPassFinalAuthorityAndCoverageChecks() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var properties = new OpenAiCompatibleProperties();
        var provider = new OpenAiCompatibleNaturalPromptProvider(RestClient.create(), mapper, properties, (PlatformModelCatalog)null);
        var method = OpenAiCompatibleNaturalPromptProvider.class.getDeclaredMethod("readEnhancementPhase", JsonNode.class, NaturalPromptInput.class,
                com.promptoptimizer.enhancement.domain.ProviderMetadata.class);
        method.setAccessible(true);
        int count = 0;
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("../../docs/testing/evidence/natural-balanced-2026-11-11"))) {
            for (var file : files.filter(path -> path.getFileName().toString().matches("call-\\d{3}\\.json")).sorted().toList()) {
                var trace = mapper.readTree(file.toFile());
                String user = trace.at("/request/messages/1/content").asText();
                if (!user.contains("taskScope")) continue;
                ObjectNode data = (ObjectNode)mapper.readTree(user.substring(user.indexOf('{')));
                if (!NaturalPromptBalancedInstructions.VERSION.equals(data.path("strategyVersion").asText())) continue;
                data.remove(List.of("taskSources", "taskScope", "inputBoundary", "planProgress"));
                var input = mapper.treeToValue(data, NaturalPromptInput.class);
                var body = mapper.readTree(trace.at("/response/choices/0/message/content").asText());
                assertThat(method.invoke(provider, body, input, new com.promptoptimizer.enhancement.domain.ProviderMetadata("fixture", "fixture", false))).isNotNull();
                count++;
            }
        }
        assertThat(count).isEqualTo(5);
    }

    private ObjectNode body() {
        var node = json.createObjectNode().put("optimizedPrompt", RAW);
        var task = node.putObject("task").put("goal", "解释缓存失效").put("deliverable", "解释");
        var requirement = task.putArray("requirements").addObject().put("text", RAW);
        requirement.putArray("dimensions").add("GOAL");
        requirement.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", RAW);
        requirement.putArray("bodyQuotes").add(RAW);
        task.putArray("additions"); task.putArray("openDecisions");
        return node;
    }

    /** 模拟传输依次经过范围、正文及至多一次补丁，不绕过真实Provider解析。 */
    private NaturalPromptDraft run(JsonNode candidate, JsonNode patch) throws Exception {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", RAW));
        var scope = Map.of("actions", List.of(Map.of("text", RAW, "timing", "CURRENT", "condition", "", "userEvidence", evidence, "detailEvidence", List.of())),
                "response", Map.of("basis", "INHERENT", "description", "解释", "userEvidence", evidence, "detailEvidence", List.of()));
        expect(server, json.valueToTree(scope)); expect(server, candidate);
        if (patch != null) expect(server, patch);
        var properties = new OpenAiCompatibleProperties(); properties.setEndpoint(URI.create(URL));
        properties.setApiKey("fixture"); properties.setModel("fixture");
        var input = new NaturalPromptInput(RAW, new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "fixture"),
                List.of(), List.of(), List.of(), null).withStrategyVersion(NaturalPromptBalancedInstructions.VERSION);
        var result = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null)
                .enhance(input, draft -> draft);
        server.verify(); return result;
    }

    private void expect(MockRestServiceServer server, JsonNode body) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", json.writeValueAsString(body))))));
        server.expect(requestTo(URL)).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
