package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 检查真实适配器的增强请求是否携带限定的代码交付指导，非实现任务的系统指令保持原范围。
 * 使用拦截端点，不调用外部模型，也不以请求包含指导替代实际编译验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class JavaImplementationRequestTest {
    private static final String ENDPOINT = "https://model.example.com/v1/chat/completions";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatiblePromptEnhancementProvider provider;

    @BeforeEach
    void prepare() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("synthetic-test-key");
        properties.setModel("synthetic-model");
        provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), objectMapper, properties);
    }

    @Test
    void includesTheSameDecimalApiContractBeforeGeneration() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.messages[0].content", containsString("Java21交付")))
                .andExpect(jsonPath("$.messages[0].content", containsString("BigDecimal.ZERO")))
                .andExpect(jsonPath("$.messages[0].content", containsString("compareTo")))
                .andRespond(withSuccess(response(), MediaType.APPLICATION_JSON));
        provider.enhance(request("使用Java21的BigDecimal实现金额比较，交付完整可编译代码，不修改项目文件。"));
        server.verify();
    }

    @Test
    void doesNotAddCodeDeliveryToATranslationSource() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.messages[0].content", not(containsString("Java21交付"))))
                .andRespond(withSuccess(response(), MediaType.APPLICATION_JSON));
        provider.enhance(request("把以下中文翻译成英文，只输出译文：Java21的BigDecimal代码可编译。"));
        server.verify();
    }

    private EnhancementProviderRequest request(String raw) {
        return new EnhancementProviderRequest(raw,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                new PromptTemplate(TemplateCode.GENERAL, "交付本次要求。", "核对原范围。", "示例"),
                List.of(), List.of(), List.of(), new EnhancementOptions(TemplateCode.AUTO, false, false, false), "synthetic-model");
    }

    private String response() throws Exception {
        String content = "{\"sections\":[{\"type\":\"BACKGROUND\",\"title\":\"背景\",\"content\":\"基于本次资料。\"},"
                + "{\"type\":\"TASK\",\"title\":\"任务\",\"content\":\"按本次目标交付。\"},"
                + "{\"type\":\"OUTPUT\",\"title\":\"输出\",\"content\":\"遵守原定格式。\"},"
                + "{\"type\":\"CONSTRAINTS\",\"title\":\"约束\",\"content\":\"不扩展任务。\"}]}";
        return objectMapper.writeValueAsString(Map.of("model", "synthetic-model", "choices",
                List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
    }
}
