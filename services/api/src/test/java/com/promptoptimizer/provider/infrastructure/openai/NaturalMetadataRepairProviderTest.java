package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 回放实际首稿与局部补丁，证明修复接线不依赖重抽正文；不宣称新鲜模型或作品通过。 */
class NaturalMetadataRepairProviderTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final URI endpoint = URI.create("https://provider.invalid/chat/completions");

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"57,U02", "57,V02", "58,U02", "58,V02", "60,U02", "60,V02"})
    void repairsActualMetadataAndKeepsTheFirstBodyExactly(String version, String id) throws Exception {
        JsonNode fixture = fixture(id); NaturalPromptInput input = input(fixture).withStrategyVersion(version); var requests = new ArrayList<JsonNode>();
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, fixture.path("firstResponse"), requests); expect(server, fixture.path("patchResponse"), requests);
        var callbacks = new AtomicInteger();
        var provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties(), (PlatformModelCatalog) null);
        var draft = provider.enhance(input, value -> { callbacks.incrementAndGet(); return checked(value, input); });
        String firstBody = json.readTree(fixture.path("firstResponse").path("choices").path(0).path("message").path("content").asText()).path("optimizedPrompt").asText();
        assertThat(draft.optimizedPrompt()).isEqualTo(firstBody); assertThat(callbacks.get()).isEqualTo(1);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("messages").path(0).path("content").asText()).isEqualTo(NaturalPromptInstructions.enhancementInstruction(version));
        assertThat(requests.get(1).path("messages").path(0).path("content").asText()).isEqualTo(NaturalPromptMetadataRepair.INSTRUCTION);
        ObjectNode first = user(requests.get(0)), second = user(requests.get(1));
        assertThat(second.path("protocolRepair").path("mode").asText()).isEqualTo("METADATA_ONLY");
        assertThat(second.path("protocolRepair").path("candidate").path("optimizedPrompt").asText()).isEqualTo(firstBody);
        second.remove("protocolRepair"); assertThat(second).isEqualTo(first);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.path("thinking").path("type").asText()).isEqualTo("enabled");
            assertThat(request.path("max_tokens").asInt()).isEqualTo(32768);
        });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"57", "58", "60"})
    void cannotRewriteTheBodyOrSpendAThirdAttemptWhileRepairingMetadata(String version) throws Exception {
        JsonNode fixture = fixture("V02"); var input = input(fixture).withStrategyVersion(version); var requests = new ArrayList<JsonNode>();
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, fixture.path("firstResponse"), requests);
        var wrong = json.createObjectNode(); wrong.putArray("changes").addObject().put("path", "/optimizedPrompt").put("value", "禁止其他文件");
        var response = json.createObjectNode(); response.putArray("choices").addObject().put("finish_reason", "stop")
                .putObject("message").put("content", json.writeValueAsString(wrong));
        expect(server, response, requests); var callbacks = new AtomicInteger();
        var provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties(), (PlatformModelCatalog) null);
        assertThatThrownBy(() -> provider.enhance(input, value -> { callbacks.incrementAndGet(); return value; })).isInstanceOf(ProviderException.class);
        assertThat(callbacks.get()).isZero(); assertThat(requests).hasSize(2); server.verify();
    }

    private JsonNode fixture(String id) throws Exception {
        try (var stream = getClass().getResourceAsStream("/natural-metadata-repair/real-first-drafts-and-patches.json")) {
            for (var item : json.readTree(stream)) if (item.path("id").asText().equals(id)) return item;
        }
        throw new IllegalStateException("Missing fixture");
    }

    private NaturalPromptInput input(JsonNode fixture) throws Exception {
        ObjectNode data = user(fixture.path("request")); data.remove(List.of("taskSources", "inputBoundary"));
        return json.treeToValue(data, NaturalPromptInput.class).withStrategyVersion("57");
    }

    private ObjectNode user(JsonNode request) throws Exception {
        String text = request.path("messages").path(1).path("content").asText(); return (ObjectNode) json.readTree(text.substring(text.indexOf('{')));
    }

    private OpenAiCompatibleProperties properties() {
        var route = new OpenAiCompatibleRouteProperties(); route.setProviderName("deepseek"); route.setEndpoint(endpoint);
        route.setApiKey("fixture"); route.setModel("deepseek-flash");
        var properties = new OpenAiCompatibleProperties(); properties.setMultiProviderEnabled(true); properties.setDefaultProvider("deepseek");
        properties.setProviders(Map.of("deepseek", route)); properties.setMaxTokens(8192); return properties;
    }

    private void expect(MockRestServiceServer server, JsonNode response, List<JsonNode> requests) throws Exception {
        server.expect(requestTo(endpoint)).andExpect(request -> requests.add(json.readTree(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8))))
                .andRespond(withSuccess(json.writeValueAsString(response), MediaType.APPLICATION_JSON));
    }

    /** 沿用实际应用的原稿、权限组装和最终覆盖检查，补丁不直接获得接受权。 */
    private NaturalPromptDraft checked(NaturalPromptDraft draft, NaturalPromptInput input) {
        var task = draft.taskUnderstanding().resolve(input);
        try {
            Method method = NaturalEnhancementService.class.getDeclaredMethod("validateBody", NaturalPromptDraft.class, List.class, String.class); method.setAccessible(true);
            String assembled = (String) method.invoke(new NaturalEnhancementService(null, null, null, null, null, null), draft, input.mandatoryRules(), input.strategyVersion());
            task.review().validateEnhancement(task, draft.optimizedPrompt()); task.review().validateEnhancement(task, assembled);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime; throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return draft;
    }
}
