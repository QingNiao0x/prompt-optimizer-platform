package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.OptimizationResultAssembler;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 通过真实适配器和结果组装器回放后段校验失败，验证安全约束与共享重试上限。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OpenAiCompatibleValidatedResponseTest {
    private static final String ENDPOINT = "https://model.example.com/chat/completions";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContextSnapshot context = new ContextSnapshot("", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "v1");
    private final PromptTemplate template = new PromptTemplate(TemplateCode.GENERAL, "明确交付", "逐项核对", "示例");
    private MockRestServiceServer server;
    private OpenAiCompatiblePromptEnhancementProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-placeholder");
        properties.setModel("test-model");
        provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRepairLegacyFindingsWithoutDroppingConditionsOrConfirmedAnswers(boolean confirmed) throws Exception {
        var invalid = content("保留原有功能", List.of());
        invalid.remove("ambiguities");
        @SuppressWarnings("unchecked")
        var sections = (List<Map<String, String>>) invalid.get("sections");
        sections.add(Map.of("type", "CLARIFICATIONS", "title", "待确认", "content",
                String.join("\n", IntStream.rangeClosed(1, 9).mapToObj(i -> "- 待确认条件" + i).toList())));
        expect(invalid);
        List<String> repaired = new ArrayList<>(IntStream.rangeClosed(1, 7).mapToObj(i -> "待确认条件" + i).toList());
        repaired.add("待确认条件8及待确认条件9");
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            assertThat(body).contains("AMBIGUITY_COUNT_INVALID");
        }).andRespond(withSuccess(completion(content("保留原有功能", repaired)), MediaType.APPLICATION_JSON));
        OptimizationResult result = provider.enhanceValidated(request(), response -> assemble(response, confirmed));
        assertThat(result.ambiguities()).anyMatch(value -> value.contains("条件9"));
        assertThat(result.optimizedPrompt()).contains("不得读取生产密钥", "保留原有功能");
        if (confirmed) assertThat(result.optimizedPrompt()).contains("只交付 Markdown 表格和验收清单");
        server.verify();
    }

    @Test
    void shouldRepairSensitiveLookingOutputWithoutSendingItsValueBackToTheModel() throws Exception {
        expect(content("password:请勿在任何位置记录真实密码", List.of()));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            assertThat(body).contains("SENSITIVE_CONTENT").doesNotContain("password:请勿");
        }).andRespond(withSuccess(completion(content("不得记录真实密码", List.of())), MediaType.APPLICATION_JSON));
        var result = provider.enhanceValidated(request(), response -> assemble(response, false));
        assertThat(result.optimizedPrompt()).contains("不得记录真实密码", "不得读取生产密钥");
        server.verify();
    }

    @Test
    void shouldShareThreeAttemptsBetweenParserAndApplicationValidation() throws Exception {
        expect(Map.of("sections", List.of()));
        expect(content("sk-DIAGNOSTIC0000000", List.of()));
        expect(content("保留原有功能", List.of()));
        assertThat(provider.enhanceValidated(request(), response -> assemble(response, false)).optimizedPrompt())
                .contains("保留原有功能").doesNotContain("sk-DIAGNOSTIC");
        server.verify();
    }

    @Test
    void shouldRejectSensitiveResponsesAfterTheSharedBudgetIsExhausted() throws Exception {
        server.expect(times(3), requestTo(ENDPOINT)).andRespond(withSuccess(
                completion(content("sk-DIAGNOSTIC0000000", List.of())), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhanceValidated(request(), response -> assemble(response, false)))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error ->
                        assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.SENSITIVE_CONTENT));
        server.verify();
    }

    @Test
    void shouldNotRetryUnchangeableServerConflictEvidence() throws Exception {
        expect(content("保留原有功能", List.of()));
        var failure = new ProviderResponseValidationException(
                ProviderResponseValidationException.Reason.AMBIGUITY_VALUE_INVALID, "context.conflicts");
        assertThatThrownBy(() -> provider.enhanceValidated(request(), response -> { throw failure; }))
                .isSameAs(failure);
        server.verify();
    }

    @Test
    void shouldNotRetryUnexpectedApplicationErrors() throws Exception {
        expect(content("保留原有功能", List.of()));
        IllegalStateException unexpected = new IllegalStateException("unexpected assembly bug");
        assertThatThrownBy(() -> provider.enhanceValidated(request(), response -> { throw unexpected; }))
                .isSameAs(unexpected);
        server.verify();
    }

    private EnhancementProviderRequest request() {
        return new EnhancementProviderRequest("整理活动执行方案", context, template, List.of(),
                List.of("不得读取生产密钥"), List.of(), EnhancementOptions.defaults(), null);
    }

    private OptimizationResult assemble(EnhancementProviderResponse response, boolean confirmed) {
        return new OptimizationResultAssembler().assemble(response, context, template, List.of(),
                confirmed ? List.of(new PlanAnswer("q1", "输出格式是什么？", "只交付 Markdown 表格和验收清单")) : List.of(),
                confirmed, List.of("不得读取生产密钥"), false, 1);
    }

    private void expect(Map<String, Object> content) throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(completion(content), MediaType.APPLICATION_JSON));
    }

    private String completion(Map<String, Object> content) throws Exception {
        return mapper.writeValueAsString(Map.of("model", "test-model", "choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("role", "assistant", "content", mapper.writeValueAsString(content))))));
    }

    private Map<String, Object> content(String constraint, List<String> ambiguities) {
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        List<Map<String, String>> sections = new ArrayList<>();
        for (String type : List.of("BACKGROUND", "TASK", "OUTPUT", "CONSTRAINTS")) {
            sections.add(Map.of("type", type, "title", type, "content", type.equals("CONSTRAINTS") ? constraint : "活动执行内容"));
        }
        content.put("sections", sections);
        content.put("ambiguities", ambiguities);
        return content;
    }
}
