package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证无独有信息的内部槽位可以留空，同时拒绝空任务、缺失字段和无实质补全的响应。
 * 请求检查覆盖实际序列化链路；不将协议通过当作真实生成质量通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OptionalSectionContentContractTest {
    @Test
    void onlyStructuredSuppressesAcceptanceDefaultsWithoutSendingRetiredExampleGuidance() throws Exception {
        for (var format : PromptFormat.values()) {
            server.expect(once(), requestTo(ENDPOINT)).andExpect(request -> {
                var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
                String user = body.path("messages").get(1).path("content").asText();
                var payload = json.readTree(user.substring(user.indexOf('{')));
                assertThat(payload.path("rawPrompt").asText()).isEqualTo(request(format, "").rawPrompt());
                assertThat(payload.path("template").path("outputGuidance").asText()).isNotBlank();
                if (format == PromptFormat.STRUCTURED) {
                    assertThat(payload.path("template").path("acceptanceGuidance").asText()).isEmpty();
                    assertThat(payload.path("template").has("exampleGuidance")).isFalse();
                } else {
                    assertThat(payload.path("template").path("acceptanceGuidance").asText()).isNotBlank();
                }
                assertThat(payload.path("taskView").has("examples")).isFalse();
                assertThat(payload.path("options").has("includeExamples")).isFalse();
                assertThat(payload.path("template").has("exampleGuidance")).isFalse();
            }).andRespond(withSuccess(response(content()), MediaType.APPLICATION_JSON));
        }
        for (var format : PromptFormat.values()) provider.enhance(request(format, ""));
        server.verify();
    }

    private static final String ENDPOINT = "https://model.example.test/v1/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatiblePromptEnhancementProvider provider;

    @BeforeEach
    void setUp() {
        var client = RestClient.builder();
        server = MockRestServiceServer.bindTo(client).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("synthetic-test-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        provider = new OpenAiCompatiblePromptEnhancementProvider(client.build(), json, properties);
    }

    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void emptySupportingSlotsDoNotForceFillerOrAnotherAttempt(PromptFormat format) throws Exception {
        server.expect(once(), requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String system = body.path("messages").get(0).path("content").asText();
            assertThat(system).contains("没有独立新增信息时使用空字符串", "方法口径、证据及局限用于支持结果")
                    .doesNotContain("title 和 content 必须为非空字符串");
            var user = body.path("messages").get(1).path("content").asText();
            assertThat(user).contains("只输出英文译文", "不要解释和示例");
        }).andRespond(withSuccess(response(content()), MediaType.APPLICATION_JSON));

        var result = provider.enhance(request(format, ""));
        assertThat(result.sections()).hasSize(4);
        assertThat(result.sections()).filteredOn(section -> section.type() != PromptSectionType.TASK)
                .allSatisfy(section -> assertThat(section.content()).isEmpty());
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.TASK)
                .singleElement().satisfies(section -> assertThat(section.content())
                        .isEqualTo("把“会议改到周五上午九点”译成英文，只输出译文，不附解释或示例。"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TASK_EMPTY", "NULL_CONTENT", "MISSING_CONTENT", "MISSING_TYPE", "DUPLICATE_TYPE",
            "EMPTY_OPTIONAL", "MISSING_COMPLETION", "UNKNOWN_PRESENTATION"})
    void optionalSupportingContentDoesNotRelaxOtherProtocolChecks(String violation) throws Exception {
        var body = content();
        var sections = (com.fasterxml.jackson.databind.node.ArrayNode) body.path("sections");
        switch (violation) {
            case "TASK_EMPTY" -> ((ObjectNode) sections.get(1)).put("content", "  ");
            case "NULL_CONTENT" -> ((ObjectNode) sections.get(0)).putNull("content");
            case "MISSING_CONTENT" -> ((ObjectNode) sections.get(0)).remove("content");
            case "MISSING_TYPE" -> sections.remove(0);
            case "DUPLICATE_TYPE" -> sections.add(sections.get(0).deepCopy());
            case "EMPTY_OPTIONAL" -> sections.addObject().put("type", "ACCEPTANCE")
                    .put("title", "验收").put("content", "");
            case "MISSING_COMPLETION" -> ((ObjectNode) body.path("assessment"))
                    .put("sufficiency", "NEEDS_COMPLETION");
            case "UNKNOWN_PRESENTATION" -> ((ObjectNode) body.path("assessment"))
                    .put("presentation", "UNKNOWN");
            default -> throw new IllegalArgumentException(violation);
        }
        server.expect(times(3), requestTo(ENDPOINT)).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(request(PromptFormat.COMPACT, "")))
                .isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void mockKeepsOriginalTaskOnceAndPreservesAnIndependentContext() {
        var mock = new MockPromptEnhancementProvider();
        var request = request(PromptFormat.COMPACT, "");
        var result = mock.enhance(request);
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.BACKGROUND)
                .singleElement().satisfies(section -> assertThat(section.content()).isEmpty());
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.TASK)
                .singleElement().satisfies(section -> assertThat(section.content()).isEqualTo(request.rawPrompt()));
        var withContext = mock.enhance(request(PromptFormat.COMPACT, "译文读者为海外合作方。"));
        assertThat(withContext.sections()).filteredOn(section -> section.type() == PromptSectionType.BACKGROUND)
                .singleElement().satisfies(section -> assertThat(section.content()).isEqualTo("译文读者为海外合作方。"));
    }

    @Test
    void sectionRepairKeepsTheSameEmptySupportingSlotContract() throws Exception {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withSuccess(response(content()), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String repair = body.path("messages").get(body.path("messages").size() - 1).path("content").asText();
            assertThat(repair).contains("无独有信息时可用空字符串", "TASK 必须有可执行内容")
                    .doesNotContain("非空字符串 title 和 content", "title 与 content 都必须是非空字符串");
        }).andRespond(withSuccess(response(content()), MediaType.APPLICATION_JSON));
        var validations = new java.util.concurrent.atomic.AtomicInteger();
        var result = provider.enhanceValidated(request(PromptFormat.COMPACT, ""), response -> {
            if (validations.incrementAndGet() == 1) throw new ProviderResponseValidationException(
                    ProviderResponseValidationException.Reason.SECTION_INVALID, "sections");
            return response;
        });
        assertThat(result.sections()).filteredOn(section -> section.type() == PromptSectionType.BACKGROUND)
                .singleElement().satisfies(section -> assertThat(section.content()).isEmpty());
        server.verify();
    }

    /** 四要素类型仍完整，实际任务与输出限制合并在同一执行指令中。 */
    private ObjectNode content() {
        var root = json.createObjectNode();
        root.putObject("assessment").put("sufficiency", "SUFFICIENT").put("presentation", "COMPACT")
                .putArray("gaps");
        var sections = root.putArray("sections");
        sections.addObject().put("type", "BACKGROUND").put("title", "背景").put("content", "");
        sections.addObject().put("type", "TASK").put("title", "任务")
                .put("content", "把“会议改到周五上午九点”译成英文，只输出译文，不附解释或示例。");
        sections.addObject().put("type", "OUTPUT").put("title", "输出").put("content", " ");
        sections.addObject().put("type", "CONSTRAINTS").put("title", "约束").put("content", "");
        root.putArray("ambiguities");
        return root;
    }

    /** 使用实际请求与响应序列化，不直接跳过模型适配器构造成功对象。 */
    private EnhancementProviderRequest request(PromptFormat format, String context) {
        return new EnhancementProviderRequest("把“会议改到周五上午九点”译成英文，只输出英文译文，不要解释和示例。",
                new ContextSnapshot(context, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new PromptTemplate(TemplateCode.GENERAL, "只交付原定译文。", "保留原意。"),
                List.of(), List.of(), List.of(), new EnhancementOptions(TemplateCode.GENERAL, false, true, format),
                "test-model");
    }

    /** 保留上游兼容端点的 message.content JSON 文本封装。 */
    private String response(JsonNode body) throws Exception {
        return json.writeValueAsString(Map.of("model", "test-model", "choices", List.of(Map.of(
                "message", Map.of("role", "assistant", "content", json.writeValueAsString(body))))));
    }
}
