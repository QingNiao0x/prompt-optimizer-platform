package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.OptimizationResultAssembler;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void malformedQuestionShapeReceivesSpecificRepairWithoutExpandingCallBudget() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var good = mapper.readTree(completion("撰写一份研究设计。", "交付完整的研究设计。"));
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                good.path("choices").get(0).path("message").path("content").asText());
        content.set("ambiguities", mapper.valueToTree(List.of(Map.of("message", "研究对象是什么？"))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) good.path("choices").get(0).path("message"))
                .put("content", mapper.writeValueAsString(content));
        server.expect(jsonPath("$.messages[1].content").value(containsString("撰写一份研究设计。")))
                .andRespond(withSuccess(mapper.writeValueAsString(good), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.messages[2].content").value(containsString("AMBIGUITY_VALUE_INVALID")))
                .andRespond(withSuccess(completion("撰写一份研究设计。", "交付完整的研究设计。"), MediaType.APPLICATION_JSON));
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create("https://execution-intent.example.test/v1/chat/completions"));
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        var request = new EnhancementProviderRequest("撰写一份研究设计。",
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new PromptTemplate(TemplateCode.GENERAL, "研究设计。", "比较公平。"),
                List.of(), List.of(), List.of(), EnhancementOptions.defaults(), "test-model");
        assertThat(new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties)
                .enhance(request).ambiguities()).isEmpty();
        server.verify();
    }

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
                new PromptTemplate(TemplateCode.GENERAL, "交付分析结果。", "依据可核查。"),
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
                new PromptTemplate(TemplateCode.GENERAL, "交付分析结果。", "依据可核查。"),
                List.of(), List.of(), List.of(), EnhancementOptions.defaults(), "test-model");
        var provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties);
        var result = provider.enhanceValidated(request, response -> new OptimizationResultAssembler().assemble(response,
                context, request.template(), List.of(), List.of(), false, List.of(), 1, request.rawPrompt()));
        assertThat(result.optimizedPrompt()).contains("输出结论、数据支撑和风险").doesNotContain("编写股票分析助手提示词");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void structuredValidTaskAndNonRepeatingOutputPassWithOneModelResponse(boolean explicitPrompt) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String raw = explicitPrompt ? "编写一份用于比较两个平台的提示词。"
                : "直接让Claude优化提示词，与本平台有何区别？";
        String task = explicitPrompt ? "提供一份可复用的对比提示词。" : "比较两种方式并说明各自适用场景。";
        server.expect(jsonPath("$.messages[0].content").value(containsString("区分当前动作、动作对象、讨论主题和交付物")))
                .andRespond(withSuccess(completion(task, "使用中文。"), MediaType.APPLICATION_JSON));
        var provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties());
        var request = structuredRequest(raw);
        assertThat(provider.enhanceValidated(request, response -> new OptimizationResultAssembler().assemble(response,
                request.context(), request.template(), List.of(), List.of(), false, List.of(), 1, raw)).optimizedPrompt())
                .contains(task, "使用中文");
        server.verify();
    }

    @Test
    void doesNotSendTheSameTargetedDeliveryRepairTwice() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String bad = completion("比较两种方式。", "另外交付一份结构化提示词。");
        server.expect(jsonPath("$.messages[0].content").exists()).andRespond(withSuccess(bad, MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.messages[2].content").value(containsString("DELIVERY_LEVEL_CONFLICT")))
                .andRespond(withSuccess(bad, MediaType.APPLICATION_JSON));
        var provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties());
        var request = structuredRequest("解释直接让Claude优化提示词与本平台有什么区别。");
        assertThatThrownBy(() -> provider.enhanceValidated(request, response -> new OptimizationResultAssembler().assemble(
                response, request.context(), request.template(), List.of(), List.of(), false, List.of(), 1, request.rawPrompt())))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error -> {
                    assertThat(error.getField()).isEqualTo("sections.OUTPUT");
                    assertThat(error.getModelAttempts()).isEqualTo(2);
                });
        server.verify();
    }

    @Test
    void structuredAssemblyKeepsOnlyTheBoundLectureDeliverable() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var base = structuredRequest("为产品宣讲准备一份提示词，也可以改为讲解稿。");
        var answers = List.of(new PlanAnswer("delivery", "本轮交付物是提示词还是讲解稿？", "只要讲解稿"));
        var request = new EnhancementProviderRequest(base.rawPrompt(), base.context(), base.template(), List.of(), answers, true,
                List.of(), List.of(), base.options(), base.model());
        server.expect(jsonPath("$.messages[0].content").value(containsString("讨论主题和交付物")))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.not(containsString("【本次提示词交付】"))))
                .andRespond(withSuccess(completion("撰写一份产品讲解稿。", "只交付讲解稿。"), MediaType.APPLICATION_JSON));
        var provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties());
        var result = provider.enhanceValidated(request, response -> new OptimizationResultAssembler().assemble(response,
                request.context(), request.template(), List.of(), answers, true, List.of(), 1, request.rawPrompt()));
        assertThat(result.optimizedPrompt()).contains("撰写一份产品讲解稿", "只交付讲解稿");
        server.verify();
    }

    private EnhancementProviderRequest structuredRequest(String raw) {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        return new EnhancementProviderRequest(raw, context,
                new PromptTemplate(TemplateCode.GENERAL, "按原定目标交付。", "保留事实。"), List.of(), List.of(),
                List.of(), new EnhancementOptions(TemplateCode.AUTO, false, false, PromptFormat.STRUCTURED), "test-model");
    }

    private OpenAiCompatibleProperties properties() {
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create("https://execution-intent.example.test/v1/chat/completions"));
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        return properties;
    }

    private String completion(String task, String output) throws Exception {
        var sections = List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", "投资分析搭档。"),
                new PromptSection(PromptSectionType.TASK, "任务", task), new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不预测点位。"));
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content",
                mapper.writeValueAsString(Map.of("sections", sections, "ambiguities", List.of())))))));
    }
}
