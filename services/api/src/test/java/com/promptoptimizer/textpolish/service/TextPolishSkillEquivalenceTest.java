package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 固定迁移前v3正文，逐项对照正常HTTP入口，覆盖文体、幅度及显式要求，不调用真实模型。 */
class TextPolishSkillEquivalenceTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String ORIGINAL_HASH = "3022e7ba9bc102a8f853a4353283e6f9995d9bdc71ae41270f6048e1bf62de98";

    /** 六种文体/幅度组合分别覆盖空要求和明确覆盖默认的格式、翻译及保护要求。 */
    static Stream<Arguments> inputs() {
        List<Arguments> cases = new ArrayList<>();
        for (Style style : Style.values()) {
            for (Depth depth : Depth.values()) {
                cases.add(Arguments.of(style, depth, "", ""));
                cases.add(Arguments.of(style, depth, "非专业读者",
                        "翻译为英文，保留原标题、三段结构和原有顺序，保留指定引文，控制在300词内。"));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("inputs")
    void resourceProducesTheSameRequestAsTheFrozenV3Guidance(Style style, Depth depth, String audience, String requirements) throws Exception {
        var json = new ObjectMapper();
        String original;
        try (var stream = getClass().getResourceAsStream("/textpolish/v3-system-before-resource.txt")) {
            original = new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        // 此哈希来自迁移前Java文本块，防止测试夹具与新资源一起改写后形成自证。
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(ORIGINAL_HASH);
        assertThat(TextPolishInstructions.SYSTEM).isEqualTo(original);
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT)); properties.setApiKey("fixture-key");
        properties.setMaxTokens(9000); properties.setTemperature(0.2); properties.setJsonResponseFormatEnabled(true);
        var model = new ModelEntry(new UUID(0, 1), "fixture", properties.getConfiguredRoutes().getFirst().key(),
                "deepseek-flash", "测试模型", true, true, 0);
        var input = new Input("原标题\n\n第一段保留引文。\n\n第二段说明条件。\n\n第三段保留承诺。", style, depth,
                audience, requirements, List.of("保留引文。"), "fixture");
        var expected = json.createObjectNode();
        expected.put("model", "deepseek-flash"); expected.put("stream", false);
        expected.putArray("messages").addObject().put("role", "system").put("content", original);
        expected.withArray("messages").addObject().put("role", "user").put("content", json.writeValueAsString(input));
        expected.put("max_tokens", 8000); expected.put("temperature", 0.2);
        expected.putObject("response_format").put("type", "json_object");
        expected.putObject("thinking").put("type", "disabled");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new OpenAiTextPolishProvider(builder.build(), json, properties, new TextPolishFidelity());
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var actual = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(actual).isEqualTo(expected);
        }).andRespond(withSuccess(json.writeValueAsString(Map.of("choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("content", json.writeValueAsString(Map.of("polishedText", input.sourceText()))))))),
                MediaType.APPLICATION_JSON));
        var result = provider.generate(input, model);
        assertThat(result.text()).isEqualTo(input.sourceText());
        assertThat(result.executionMetadata().strategyVersion()).isEqualTo("text-polish-v3");
        assertThat(result.executionMetadata().skillVersion()).isEqualTo("text-polish-v3");
        assertThat(result.executionMetadata().resourceHash()).isEqualTo(ORIGINAL_HASH);
        server.verify();
    }
}
