package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 旧责任建议不再作为删题依据；畸形／缺省标签不改变公共协议或新增502。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class QuestionResponsibilityProviderTest {
    @ParameterizedTest
    @ValueSource(strings = {"valid", "missing", "array", "badIds", "unknownOwner"})
    void optionalAdviceDoesNotChangeThePublicContractOrRetryBudget(String variant) throws Exception {
        var mapper = new ObjectMapper();
        var response = mapper.createObjectNode().put("summary", "确认交付方式。");
        var question = response.putArray("questions").addObject().put("id", "packaging")
                .put("question", "希望怎样使用这份实现？").put("hint", "函数或命令行入口。")
                .put("type", "SINGLE_CHOICE").put("allowCustomAnswer", true);
        question.putArray("examples");
        var options = question.putArray("options");
        options.addObject().put("id", "fn").put("label", "函数").put("description", "导出函数。")
                .put("answer", "交付函数").put("recommended", false).put("recommendationReason", "");
        options.addObject().put("id", "cli").put("label", "命令行").put("description", "命令行入口。")
                .put("answer", "交付命令行工具").put("recommended", false).put("recommendationReason", "");
        if (!variant.equals("missing")) {
            if (variant.equals("array")) question.putArray("responsibility").add("invalid");
            else {
                var advice = question.putObject("responsibility").put("owner", variant.equals("unknownOwner") ? "MODEL" : "EXECUTOR")
                        .put("impact", "CODE_PACKAGING_ONLY").put("rawGoal", "用TypeScript实现文本去重。")
                        .put("question", question.path("question").asText());
                var ids = advice.putArray("optionIds"); ids.add("fn");
                if (variant.equals("badIds")) ids.addNull(); else ids.add("cli");
            }
        }
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(java.net.URI.create("https://model.example.test/v1/chat/completions"));
        properties.setApiKey("test-only-key"); properties.setModel("deepseek-flash");
        server.expect(requestTo(properties.getEndpoint())).andRespond(withSuccess(mapper.writeValueAsString(Map.of(
                "model", "deepseek-flash", "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", response.toString()))))), MediaType.APPLICATION_JSON));
        var input = new PlanningProviderRequest("用TypeScript实现文本去重。", "", List.of());
        var result = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties).plan(input);
        server.verify();
        PlanQuestion parsed = result.questions().getFirst();
        var filtered = new PlanQuestionFilter().filter(result.questions(), input);
        assertThat(filtered).isEmpty();
        assertThat(mapper.writeValueAsString(result)).doesNotContain("responsibility", "rawGoal", "CODE_PACKAGING_ONLY");
        assertThat(parsed.options()).hasSize(2);
    }
}
