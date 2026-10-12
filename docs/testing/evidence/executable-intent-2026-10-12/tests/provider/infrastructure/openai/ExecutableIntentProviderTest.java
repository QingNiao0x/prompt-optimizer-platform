package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.service.impl.OptimizationResultAssembler;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;

/**
 * 实际适配器在交付层级错误时修复原段落，不能把错误元提示词当成成功结果返回。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ExecutableIntentProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"empty", "description", "warning"})
    void onlyEmptyAuxiliaryContextIsOmittedFromTheModelInput(String scenario) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(content().string(org.hamcrest.Matchers.any(String.class)))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    String message = mapper.readTree(body).path("messages").get(1).path("content").asText();
                    var payload = mapper.readTree(message.substring(message.indexOf('{')));
                    assertThat(payload.has("conversationHistory")).isFalse();
                    assertThat(payload.has("context")).isEqualTo(!scenario.equals("empty"));
                    if (scenario.equals("description")) {
                        assertThat(payload.path("context").path("customDescription").asText()).isEqualTo("研究地区：广东省");
                    }
                    if (scenario.equals("warning")) {
                        assertThat(payload.path("context").path("warnings").get(0).asText()).isEqualTo("已选资料解析失败");
                    }
                })
                .andRespond(withSuccess(completion("按用户问题分析已有数据。", "输出分析结果。"), MediaType.APPLICATION_JSON));
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create("https://execution-intent.example.test/v1/chat/completions"));
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        var context = new ContextSnapshot(scenario.equals("description") ? "研究地区：广东省" : "",
                List.of(), List.of(), List.of(), List.of(),
                scenario.equals("warning") ? List.of("已选资料解析失败") : List.of(), List.of(), "test");
        var request = new EnhancementProviderRequest("你是一名数据分析搭档。", context,
                new PromptTemplate(TemplateCode.GENERAL, "交付分析结果。", "依据可核查。", "示例。"),
                List.of(), List.of(), List.of(), EnhancementOptions.defaults(), "test-model");
        new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties).enhance(request);
        server.verify();
    }

    @Test
    void repairsTheRolePromptAtTheRealProviderAssemblerBoundary() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(jsonPath("$.messages[0].content").value(containsString("平台生成提示词与下游交付内容是两个层级")))
                .andRespond(withSuccess(completion("编写股票分析助手提示词。", "交付角色提示词。"), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.messages[2].content").value(containsString("DELIVERY_LEVEL_CONFLICT")))
                .andRespond(withSuccess(completion("作为投资分析搭档，按用户问题分析已有数据。", "输出结论、数据支撑和风险。"), MediaType.APPLICATION_JSON));
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create("https://execution-intent.example.test/v1/chat/completions"));
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        var request = new EnhancementProviderRequest("你是一名投资分析搭档。结论先行，不预测点位。", context,
                new PromptTemplate(TemplateCode.GENERAL, "交付分析结果。", "依据可核查。", "示例。"),
                List.of(), List.of(), List.of(), EnhancementOptions.defaults(), "test-model");
        var provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties);
        var result = provider.enhanceValidated(request, response -> new OptimizationResultAssembler().assemble(response,
                context, request.template(), List.of(), List.of(), false, List.of(), false, 1, request.rawPrompt()));
        assertThat(result.optimizedPrompt()).contains("输出结论、数据支撑和风险").doesNotContain("编写股票分析助手提示词");
        server.verify();
    }

    private String completion(String task, String output) throws Exception {
        var sections = List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", "投资分析搭档。"),
                new PromptSection(PromptSectionType.TASK, "任务", task), new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不预测点位。"));
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content",
                mapper.writeValueAsString(Map.of("sections", sections, "ambiguities", List.of())))))));
    }
}
