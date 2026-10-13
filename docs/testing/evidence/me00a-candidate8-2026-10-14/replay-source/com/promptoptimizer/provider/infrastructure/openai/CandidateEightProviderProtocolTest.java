package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 真实失败原件通过生产 Provider 的有限修复循环回放，检查协议拒绝与请求状态，Mock 不产生付费调用。
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateEightProviderProtocolTest {
    private static final String ENDPOINT = "https://offline.example.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldRejectActualObjectAmbiguitiesWithoutConvertingOrDroppingThem() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var requests = new ArrayList<JsonNode>();
        server.expect(times(3), requestTo(ENDPOINT)).andExpect(http -> requests.add(json.readTree(
                ((org.springframework.mock.http.client.MockClientHttpRequest) http).getBodyAsString())))
                .andRespond(withSuccess(json.writeValueAsString(fixture("traces/178.json").path("response")), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider(builder).enhance(request()))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure -> {
                    assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.AMBIGUITY_VALUE_INVALID);
                    assertThat(failure.getModelAttempts()).isEqualTo(3);
                });
        server.verify();
        assertThat(requests).hasSize(3);
        for (var wire : requests) {
            String system = wire.path("messages").get(0).path("content").asText();
            assertThat(system).contains("非空字符串", "message/questionId", "ambiguityReferences");
            String user = wire.path("messages").get(1).path("content").asText();
            var view = json.readTree(user.substring(user.indexOf('{'))).path("taskView");
            assertThat(view.path("executionRequirements")).hasSize(4);
            assertThat(view.path("pendingDecisions")).hasSize(5);
            for (var decision : view.path("pendingDecisions")) {
                assertThat(decision.path("designDelegated").asBoolean()).isTrue();
                assertThat(decision.path("scope").asText()).isEqualTo("UNRESOLVED");
            }
        }
    }

    @Test
    void shouldKeepTheActualProfessionalOnlyAssessmentConflictRejected() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(3), requestTo(ENDPOINT)).andRespond(withSuccess(
                json.writeValueAsString(fixture("traces/180.json").path("response")), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider(builder).enhanceValidated(request(), response -> {
            response.assessment().validateModelDecisions(response.ambiguities());
            return response;
        })).isInstanceOfSatisfying(ProviderResponseValidationException.class, failure -> {
            assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.ASSESSMENT_DECISION_CONFLICT);
            assertThat(failure.getModelAttempts()).isEqualTo(3);
        });
        server.verify();
    }

    private OpenAiCompatiblePromptEnhancementProvider provider(RestClient.Builder builder) {
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("offline-fixture-only");
        properties.setModel("test-model");
        return new OpenAiCompatiblePromptEnhancementProvider(builder.build(), json, properties);
    }

    /** 复用保存的实际题答，模板和空上下文仅用于离线协议回放，不改写原件。 */
    private EnhancementProviderRequest request() throws Exception {
        var input = fixture("q05-flash/input.json");
        var answers = new ArrayList<PlanAnswer>();
        for (var answer : input.path("planConfirmation").path("answers")) answers.add(json.treeToValue(answer, PlanAnswer.class));
        return new EnhancementProviderRequest(input.path("rawPrompt").asText(),
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付方法正文。", "保留原研究范围。", "遵守原文。"),
                List.of(), answers, true, List.of(), List.of(),
                new EnhancementOptions(TemplateCode.RESEARCH_ANALYSIS, false, true, false), "test-model");
    }

    private JsonNode fixture(String name) throws Exception {
        Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (base != null) {
            Path file = base.resolve("docs/testing/evidence/me00a-candidate7-2026-10-13").resolve(name);
            if (Files.isRegularFile(file)) return json.readTree(Files.readString(file));
            base = base.getParent();
        }
        throw new IllegalStateException("缺少候选七原件：" + name);
    }
}
