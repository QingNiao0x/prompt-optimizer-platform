package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 用330分类冲突、331损坏JSON回放真实修复路径；合法第三响应仅为离线夹具，不改写原件。 */
class CandidateTwentySevenRepairProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "https://model.example.com/v1/chat/completions";
    private MockRestServiceServer server;
    private OpenAiCompatiblePromptEnhancementProvider provider;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(java.net.URI.create(ENDPOINT));
        properties.setApiKey("synthetic-test-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        properties.setProviderName("test-provider");
        provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), JSON, properties);
    }

    @Test
    void aJsonFailureStillGetsTheWholeAssessmentContractOnTheFinalRepair() throws Exception {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withSuccess(completion(original(330)), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(ENDPOINT)).andExpect(request -> {
            JsonNode body = JSON.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("messages")).hasSize(3);
            assertThat(body.path("messages").get(2).path("content").asText()).contains("ASSESSMENT_DECISION_CONFLICT");
        }).andRespond(withSuccess(completion(original(331)), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(ENDPOINT)).andExpect(request -> {
            JsonNode body = JSON.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("messages")).hasSize(3);
            assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
            String repair = body.path("messages").get(2).path("content").asText();
            // 损坏JSON没有可相信的字段归因；通用修复仍须包含完整契约，但不携带过期错误或模型正文。
            assertThat(repair).contains("assessment", "sufficiency", "gaps", "presentation", "USER_DECISION",
                    "SUFFICIENT", "NEEDS_COMPLETION", "COMPACT", "STRUCTURED", "missing", "completion", "basis");
            assertThat(repair).doesNotContain("ASSESSMENT_DECISION_CONFLICT", "Plan 组问答交互的轮次与终止方式未定");
        }).andRespond(withSuccess(completion(validFixture()), MediaType.APPLICATION_JSON));
        var result = provider.enhanceValidated(request(), response -> {
            response.assessment().validateModelDecisions(response.ambiguities()); return response;
        });
        assertThat(result.ambiguities()).singleElement().asString().contains("分类由用户决定");
        server.verify();
    }

    @Test
    void allThreeCapturedFailuresRemainRejectedWithoutAFourthCall() throws Exception {
        for (int sequence : List.of(330, 331, 332)) {
            server.expect(once(), requestTo(ENDPOINT)).andRespond(withSuccess(completion(original(sequence)), MediaType.APPLICATION_JSON));
        }
        assertThatThrownBy(() -> provider.enhanceValidated(request(), response -> {
            response.assessment().validateModelDecisions(response.ambiguities()); return response;
        })).isInstanceOf(ProviderResponseValidationException.class);
        server.verify();
    }

    @Test
    void invalidAssessmentEnumCannotBecomeASuccess() throws Exception {
        var invalid = JSON.readTree(validFixture());
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("assessment")).put("presentation", "ANYTHING");
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(once(), requestTo(ENDPOINT)).andRespond(withSuccess(completion(invalid.toString()), MediaType.APPLICATION_JSON));
        }
        assertThatThrownBy(() -> provider.enhance(request())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    /** 仅在测试中补一条有原题答依据的分类缺口，避免用清空提醒假装协议修复。 */
    private String validFixture() throws Exception {
        JsonNode value = JSON.readTree(original(330));
        ArrayNode gaps = (ArrayNode) value.path("assessment").path("gaps");
        gaps.addObject().put("kind", "USER_DECISION").put("missing", "四类任务的分类由用户保留选择，尚未确认")
                .put("completion", "保留分类未决状态，仅提出受委托的评分设计")
                .put("basis", "实际回答：只委托评分设计，分类由我决定。");
        return value.toString();
    }

    /** 固定同一真实题目，网络由Spring测试传输替代，绝不消耗真实调用。 */
    private EnhancementProviderRequest request() throws Exception {
        var input = JSON.readTree(repo().resolve("docs/testing/evidence/me00a-candidate26-r2-live-2026-10-17/real/Q05-PARTIAL-SCORING/input.json").toFile());
        return new EnhancementProviderRequest(input.path("rawPrompt").asText(),
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.AUTO, "保持研究目标", "保留真实未决选择"),
                List.of(), List.of(), List.of(), EnhancementOptions.defaults(), "test-model");
    }

    /** 只读已归档的真实模型原件；外层测试供应商名称不影响结构错误回放。 */
    private String original(int sequence) throws Exception {
        return JSON.readTree(repo().resolve("docs/testing/evidence/me00a-candidate26-r2-live-2026-10-17/real/traces/" + sequence + ".json").toFile())
                .path("response").path("choices").get(0).path("message").path("content").asText();
    }

    private String completion(String content) throws Exception {
        return JSON.writeValueAsString(Map.of("model", "test-model", "choices",
                List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
    }

    private static Path repo() {
        Path value = Path.of("").toAbsolutePath();
        while (value != null && !Files.isDirectory(value.resolve(".git"))) value = value.getParent();
        if (value == null) throw new IllegalStateException("Repository not found");
        return value;
    }
}
