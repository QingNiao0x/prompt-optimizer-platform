package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.logging.*;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** HTTP协议、失败计量和有界修复使用本地Mock服务器，不访问外部服务。 */
class OpenAiCompatibleNaturalPromptProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;
    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-api-key");
        properties.setModel("fixture");
        properties.setModels(List.of("fixture"));
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }
    @Test
    void acceptsPlainCanonicalDocumentWithoutFourPartProtocol() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            var input = json.readTree(body.path("messages").get(1).path("content").asText().split("\n", 2)[1]);
            assertThat(input.has("template")).isFalse();
            assertThat(input.has("sections")).isFalse();
            assertThat(input.has("taskSources")).isFalse();
            assertThat(input.has("strategyVersion")).isFalse();
            assertThat(input.path("rawPrompt").asText()).isEqualTo("写邮件");
        }).andRespond(withSuccess(response("{\"optimizedPrompt\":\"完整自然正文\",\"framework\":\"APE\"}"), MediaType.APPLICATION_JSON));
        var result = provider.enhance(input(), Function.identity());
        assertThat(result.optimizedPrompt()).isEqualTo("完整自然正文");
        server.verify();
    }
    @Test
    void parsingFailureAndSuccessAreBothCountedWithActualUsage() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("invalid"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{\"optimizedPrompt\":\"正文\",\"framework\":\"ICIO\"}"), MediaType.APPLICATION_JSON));
        var facts = new ArrayList<ModelCallObservation>();
        try (var scope = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(new UUID(0, 1), (tenant, fact) -> facts.add(fact)))) {
            provider.enhance(input(), Function.identity());
        }
        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        assertThat(facts).allSatisfy(fact -> assertThat(fact.usage().totalTokens()).isEqualTo(15L));
        server.verify();
    }
    @Test
    void invalidResponsesStopAfterOneRepair() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{}"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input(), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }
    @Test
    void timeoutDoesNotConsumeARepairCall() {
        server.expect(requestTo(ENDPOINT)).andRespond(request -> { throw new java.net.SocketTimeoutException("offline"); });
        assertThatThrownBy(() -> provider.enhance(input(), Function.identity()))
                .isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.getFailureType()).isEqualTo(ProviderFailureType.TIMEOUT));
        server.verify();
    }
    @Test
    void authenticationFailureDoesNotRetry() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> provider.plan(input(), Function.identity()))
                .isInstanceOfSatisfying(ProviderException.class, ex -> assertThat(ex.getFailureType()).isEqualTo(ProviderFailureType.AUTHENTICATION));
        server.verify();
    }
    @Test
    void planCanReturnZeroQuestionsUsingItsOwnProtocol() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{\"summary\":\"任务已充分\",\"questions\":[]}"), MediaType.APPLICATION_JSON));
        assertThat(provider.plan(input(), Function.identity()).questions()).isEmpty();
        server.verify();
    }

    @Test
    void understandingReferencesAreResolvedFromServerSourcesAndKeptInternalToTheDraft() throws Exception {
        String task = "{\"goal\":\"写邮件\",\"deliverable\":\"邮件\",\"requirements\":[{\"text\":\"写邮件\","
                + "\"evidence\":[{\"sourceId\":\"rawPrompt\",\"quote\":\"写邮件\",\"origin\":\"BOUND_PLAN_ANSWER\"}]}]}";
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("{\"optimizedPrompt\":\"完整邮件要求\",\"framework\":\"APE\","
                + "\"taskUnderstanding\":" + task + "}"), MediaType.APPLICATION_JSON));
        var result = provider.enhance(input(), Function.identity());
        assertThat(result.taskUnderstanding().requirements().getFirst().evidence().getFirst().origin())
                .isEqualTo(com.promptoptimizer.provider.domain.NaturalTaskSource.Kind.ORIGINAL_REQUEST);
        server.verify();
    }

    @Test
    void absentEvidenceConsumesTheExistingRepairBudgetAndNeverBecomesASuccessfulDraft() throws Exception {
        String content = "{\"optimizedPrompt\":\"邮件要求\",\"framework\":\"APE\",\"taskUnderstanding\":{\"goal\":\"写邮件\","
                + "\"requirements\":[{\"text\":\"虚构确认\",\"evidence\":[{\"sourceId\":\"actualAnswers.0\",\"quote\":\"用户已确认\"}]}]}}";
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input(), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void secondVersionUsesTaskDrivenGuidanceAndReturnsUnderstandingInTheSameCall() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("messages").get(0).path("content").asText()).contains("真正影响完成任务的缺口", "平台统一组装");
            var payload = json.readTree(body.path("messages").get(1).path("content").asText().split("\n", 2)[1]);
            assertThat(payload.path("taskSources").get(0).path("id").asText()).isEqualTo("rawPrompt");
        }).andRespond(withSuccess(response("{\"optimizedPrompt\":\"交付邮件正文，保留原定目的。\",\"framework\":\"APE\","
                + "\"taskUnderstanding\":{\"goal\":\"写邮件\",\"deliverable\":\"邮件正文\",\"requirements\":[],\"additions\":[],\"openDecisions\":[]}}"), MediaType.APPLICATION_JSON));
        assertThat(provider.enhance(input().withStrategyVersion("2"), Function.identity()).taskUnderstanding().goal()).isEqualTo("写邮件");
        server.verify();
    }

    @Test
    void secondVersionCannotAcceptAnOldResponseWithoutItsTaskRecord() throws Exception {
        String old = response("{\"optimizedPrompt\":\"邮件正文\",\"framework\":\"APE\"}");
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(old, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(old, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input().withStrategyVersion("2"), Function.identity())).isInstanceOf(ProviderException.class);
        server.verify();
    }
    private NaturalPromptInput input() {
        return new NaturalPromptInput("写邮件", new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(), List.of(), false, List.of(), null).withStrategyVersion("1");
    }
    private String response(String content) throws Exception {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", content))), "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }
}
