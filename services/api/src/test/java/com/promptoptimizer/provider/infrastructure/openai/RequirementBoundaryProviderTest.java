package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.service.impl.OptimizationResultAssembler;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 在真实适配器及组装回调之间验证边界错误的有限修复，不为违约另建模型调用链。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RequirementBoundaryProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OptimizationResultAssembler assembler = new OptimizationResultAssembler();

    @Test
    void dataExceptionIsRepairedInsideTheExistingAttemptBudget() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var request = request();
        server.expect(jsonPath("$.messages[1].content").value(containsString("requirementBoundaryGuidance")))
                .andRespond(withSuccess(completion("若需要验证，可以使用明确标注为虚构的数据。"), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.messages[2].content").value(containsString("DATA_PROHIBITION_CONFLICT")))
                .andExpect(jsonPath("$.messages[2].content").value(containsString("删除自行新增")))
                .andRespond(withSuccess(completion("不得使用假设数据，测试也不例外。"), MediaType.APPLICATION_JSON));
        var result = provider(builder).enhanceValidated(request, response -> assembler.assemble(response,
                request.context(), request.template(), List.of(), List.of(), false, List.of(), 1, request.rawPrompt()));
        assertThat(result.optimizedPrompt()).contains("不得使用假设数据").doesNotContain("可以使用明确标注为虚构");
        server.verify();
    }

    @Test
    void repeatedBoundaryConflictStopsAfterThreeAttemptsAndDoesNotPretendSuccess() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(3), jsonPath("$.messages[1].content").exists())
                .andRespond(withSuccess(completion("可以使用虚构数据验证。"), MediaType.APPLICATION_JSON));
        var request = request();
        assertThatThrownBy(() -> provider(builder).enhanceValidated(request, response -> assembler.assemble(response,
                request.context(), request.template(), List.of(), List.of(), false, List.of(), 1, request.rawPrompt())))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure -> {
                    assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.DATA_PROHIBITION_CONFLICT);
                    assertThat(failure.getModelAttempts()).isEqualTo(3);
                });
        server.verify();
    }

    private EnhancementProviderRequest request() {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), "SUCCESS", List.of(), List.of(), List.of(), "v1");
        return new EnhancementProviderRequest("整理审批规则为开发提示词，不得使用假设数据。", context,
                new PromptTemplate(TemplateCode.GENERAL, "交付提示词。", "符合规则。"), List.of(), List.of(), false,
                List.of(), List.of(), new EnhancementOptions(TemplateCode.GENERAL, false, true), "test-model");
    }

    private OpenAiCompatiblePromptEnhancementProvider provider(RestClient.Builder builder) {
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create("https://requirement-boundary.example.test/v1/chat/completions"));
        properties.setApiKey("test-boundary-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        return new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties);
    }

    private String completion(String task) throws Exception {
        var sections = List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", "原定审批规则。"),
                new PromptSection(PromptSectionType.TASK, "任务", "生成开发提示词。" + task),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付可直接使用的开发提示词。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "保留规则条件。"));
        return mapper.writeValueAsString(Map.of("model", "test-model", "choices", List.of(Map.of("message",
                Map.of("content", mapper.writeValueAsString(Map.of("sections", sections, "ambiguities", List.of())))))));
    }
}
