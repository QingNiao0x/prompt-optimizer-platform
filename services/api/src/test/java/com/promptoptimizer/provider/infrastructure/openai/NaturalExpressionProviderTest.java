package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.List;
import java.util.Map;
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

/** 正常Provider入口核对指导选择、原输入和协议保护，模拟响应不作为语义质量证明。 */
class NaturalExpressionProviderTest {
    private static final String URL = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void replacesWritingGuidanceWhileKeepingTheV5WireContract() {
        String marker = "只返回以下完整JSON对象";
        String previous = NaturalPromptProtocolRepairInstructions.ENHANCE;
        String current = NaturalPromptInstructions.enhancementInstruction(NaturalPromptExpressionInstructions.VERSION);
        assertThat(current.substring(current.indexOf(marker))).isEqualTo(previous.substring(previous.indexOf(marker)));
        assertThat(current).contains("明确指代、先后顺序和条件归属", "作为拟议做法表达")
                .doesNotContain("不能另设要向用户展示或交付的内容");
        assertThat(NaturalPromptInstructions.planningInstruction(NaturalPromptExpressionInstructions.VERSION))
                .isEqualTo(NaturalPromptProtocolRepairInstructions.PLAN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"请帮我查询全球使用AI最多的职业/行业", "只阅读附件，理解后等待，不改文件。", "请写分析报告，只给报告，不执行修复。"})
    void normalEntryAlwaysGeneratesAndRetainsTheFullRequest(String raw) throws Exception {
        var input = input(raw);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, scope(input), input, NaturalPromptPlanContextInstructions.PREPARE);
        expect(server, enhancement(input, raw), input, NaturalPromptExpressionInstructions.ENHANCE);
        var result = provider(builder.build()).enhance(input, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(raw);
        assertThat(result.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    @Test
    void missingBodyCoverageStillFailsAfterAProtocolRepair() throws Exception {
        var input = input("写一则通知，保留例外条件。");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, scope(input), input, NaturalPromptPlanContextInstructions.PREPARE);
        var invalid = enhancement(input, input.rawPrompt());
        ((ObjectNode) invalid.at("/task/requirements/0")).putArray("bodyQuotes").add("正文没有这句话");
        expect(server, invalid, input, null);
        expect(server, invalid, input, null);
        assertThatThrownBy(() -> provider(builder.build()).enhance(input, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        })).isInstanceOf(ProviderException.class);
        server.verify();
    }

    private NaturalPromptInput input(String raw) {
        return new NaturalPromptInput(raw, new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "fixture"),
                List.of(), List.of(), List.of(), null).withStrategyVersion(NaturalPromptExpressionInstructions.VERSION);
    }

    private OpenAiCompatibleNaturalPromptProvider provider(RestClient client) {
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(URL));
        properties.setApiKey("fixture");
        properties.setModel("fixture");
        return new OpenAiCompatibleNaturalPromptProvider(client, json, properties, (PlatformModelCatalog) null);
    }

    private ObjectNode scope(NaturalPromptInput input) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "",
                "userEvidence", evidence, "detailEvidence", List.of())), "response", Map.of("basis", "UNSPECIFIED", "description", "",
                "userEvidence", List.of(), "detailEvidence", List.of())));
    }

    private ObjectNode enhancement(NaturalPromptInput input, String body) {
        var root = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        var task = root.putObject("task").put("goal", input.rawPrompt()).put("deliverable", "原定结果").put("inputReadiness", "SUFFICIENT");
        var item = task.putArray("requirements").addObject().put("text", input.rawPrompt());
        item.putArray("dimensions").add("GOAL");
        item.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", input.rawPrompt());
        item.putArray("bodyQuotes").add(body);
        task.putArray("additions");
        task.putArray("openDecisions");
        return root;
    }

    /** 核对实际发出的system与完整原始提示词，响应经真实DTO与覆盖读取器。 */
    private void expect(MockRestServiceServer server, JsonNode content, NaturalPromptInput input, String instruction) throws Exception {
        String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(URL)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            if (instruction != null) assertThat(sent.at("/messages/0/content").asText()).isEqualTo(instruction);
            String user = sent.at("/messages/1/content").asText();
            var payload = json.readTree(user.substring(user.indexOf('{')));
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
