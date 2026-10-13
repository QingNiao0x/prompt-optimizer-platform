package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 从 Provider 的实际序列化请求核对研究主题来源与局部指导，响应由本地桩提供，不验收模型质量。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ResearchEvaluationRequestTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothEnhancementEntriesSendGuidanceAndTheSameSourceAnchor(boolean planned) throws Exception {
        var json = new ObjectMapper();
        var client = RestClient.builder();
        var server = MockRestServiceServer.bindTo(client).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create("https://model.example.com/v1/chat/completions"));
        properties.setApiKey("synthetic-test-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        var provider = new OpenAiCompatiblePromptEnhancementProvider(client.build(), json, properties);
        String raw = "为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。评价准确性和可执行性。";

        server.expect(requestTo(properties.getEndpoint())).andExpect(request -> {
            var wire = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String userMessage = wire.path("messages").get(1).path("content").asText();
            var payload = json.readTree(userMessage.substring(userMessage.indexOf('{')));
            String instruction = payload.path("rewriteStrategy").path("instruction").asText();
            assertThat(instruction).contains("本任务评价对象绑定", "EFFECTIVE_USER_INPUT[20,35)");
            assertThat(wire.path("messages").get(0).path("content").asText()).contains(instruction);
            var anchors = payload.path("taskView").path("userMeaningAnchors");
            assertThat(anchors).hasSize(2);
            var topic = anchors.get(1);
            assertThat(topic.path("role").asText()).isEqualTo("RESEARCH_TOPIC");
            assertThat(topic.path("origin").asText()).isEqualTo("EFFECTIVE_USER_INPUT");
            assertThat(raw.substring(topic.path("start").asInt(), topic.path("end").asInt()))
                    .isEqualTo(topic.path("text").asText());
        }).andRespond(withSuccess(json.writeValueAsString(Map.of("model", "test-model", "choices", List.of(Map.of(
                "message", Map.of("role", "assistant", "content", """
                {"assessment":{"sufficiency":"SUFFICIENT","gaps":[],"presentation":"COMPACT"},
                 "sections":[
                 {"type":"BACKGROUND","title":"背景","content":"研究Plan问答对提示词质量的影响，面向研究生。"},
                 {"type":"TASK","title":"任务","content":"撰写研究方法部分，评价提示词的准确性和可执行性。"},
                 {"type":"OUTPUT","title":"输出","content":"交付方法正文。"},
                 {"type":"CONSTRAINTS","title":"约束","content":"不编造研究事实。"}],"ambiguities":[]}
                """))))), MediaType.APPLICATION_JSON));

        var answers = planned ? List.of(new PlanAnswer("status", "研究是否已经实施？", "尚未实施。"))
                : List.<PlanAnswer>of();
        provider.enhance(new EnhancementProviderRequest(raw,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new PromptTemplate(TemplateCode.GENERAL, "按原文交付。", "保留原意。", "遵守示例选择。"),
                List.of(), answers, planned, List.of(), List.of(),
                new EnhancementOptions(TemplateCode.GENERAL, false, true, false), "test-model"));
        server.verify();
    }
}
