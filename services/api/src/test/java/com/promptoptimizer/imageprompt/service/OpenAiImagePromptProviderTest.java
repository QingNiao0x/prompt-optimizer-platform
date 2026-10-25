package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.service.ImagePromptProvider.*;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 真正的多模态 HTTP 结构、有界协议修复和不重试超时；全部响应由本地受控服务器提供。 */
class OpenAiImagePromptProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MockRestServiceServer server;
    private OpenAiImagePromptProvider provider;
    private final UUID asset = UUID.randomUUID();
    private final List<ModelCallObservation> calls = new ArrayList<>();
    private ModelCallAuditContext.Scope auditScope;
    @BeforeEach void setup() {
        auditScope = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(UUID.randomUUID(), (tenant, call) -> calls.add(call)));
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT)); properties.setApiKey("fixture-api-key"); properties.setModel("fixture");
        var route = properties.getConfiguredRoutes().getFirst();
        var catalog = mock(PlatformModelCatalog.class);
        when(catalog.resolve("fixture")).thenReturn(new PlatformModelCatalog.ModelEntry(UUID.randomUUID(), "fixture", route.key(), "fixture", "测试", true, true, 0));
        provider = new OpenAiImagePromptProvider(builder.build(), json, properties, catalog);
    }
    @AfterEach void closeAuditScope() { auditScope.close(); }
    @Test void sendsPixelsAsImagePartsWithRoleAndModeInsteadOfEmbeddingBase64InText() throws Exception {
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            var parts = body.path("messages").get(1).path("content");
            assertThat(parts.get(1).path("type").asText()).isEqualTo("image_url");
            assertThat(parts.get(1).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,AQID");
            var input = json.readTree(parts.get(0).path("text").asText());
            assertThat(input.path("input").path("mode").asText()).isEqualTo("REFERENCE_RECONSTRUCTION");
            assertThat(input.path("imageSources").get(0).path("role").asText()).isEqualTo("STYLE");
            assertThat(parts.get(0).path("text").asText()).doesNotContain("base64");
        }).andRespond(withSuccess(response("完整画面还原提示词"), MediaType.APPLICATION_JSON));
        assertThat(provider.invoke(call()).draft().optimizedPrompt()).isEqualTo("完整画面还原提示词"); server.verify();
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.operation()).isEqualTo("image.generate"); assertThat(call.successful()).isTrue();
            assertThat(call.usage().totalTokens()).isEqualTo(15);
            assertThat(call.toString()).doesNotContain("AQID", "完整画面还原提示词");
        });
    }
    @Test void invalidProtocolStopsAfterOneRepair() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(envelope("{}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(envelope("{}"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.invoke(call())).isInstanceOf(ProviderException.class); server.verify();
        assertThat(calls).hasSize(2).extracting(ModelCallObservation::eventId).doesNotHaveDuplicates();
        assertThat(calls).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        assertThat(calls).extracting(ModelCallObservation::willRetry).containsExactly(true, false);
        assertThat(calls).allSatisfy(call -> assertThat(call.usage().totalTokens()).isEqualTo(15));
    }
    @Test void timeoutAndContentRefusalDoNotAutomaticallyRetry() {
        server.expect(requestTo(ENDPOINT)).andRespond(request -> { throw new java.net.SocketTimeoutException("fixture"); });
        assertThatThrownBy(() -> provider.invoke(call())).isInstanceOfSatisfying(ProviderException.class,
                e -> assertThat(e.getFailureType()).isEqualTo(ProviderFailureType.TIMEOUT)); server.verify();
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.successful()).isFalse(); assertThat(call.failureType()).isEqualTo("TIMEOUT");
            assertThat(call.willRetry()).isFalse(); assertThat(call.usage()).isNull();
        });
    }
    @Test void sensitiveResultIsBlockedBeforeHistoryAndDisplay() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response("api_key=fixtureSecretCredential123"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.invoke(call())).isInstanceOf(ProviderException.class); server.verify();
    }
    @Test void inventedObservationSourceIsRejected() {
        var draft = new ProviderDraft("图像", "提示词", List.of(new Observation(UUID.randomUUID(), "主体", "一只鸟", Visibility.OBSERVED)), List.of(), List.of());
        assertThatThrownBy(() -> provider.validate(draft, call())).isInstanceOf(ProviderException.class);
    }
    private Call call() {
        var input = new Input(Mode.REFERENCE_RECONSTRUCTION, "fixture", List.of(new ImageRef(asset, ImageRole.STYLE)), "", "", "", Language.ZH, "通用");
        return new Call(Stage.EXTRACT, input, List.of(new ImageData(new ImageSource(asset, "fixture.png", "hash", ImageRole.STYLE, 2, 2,
                Instant.now().plusSeconds(60)), "image/png", new byte[]{1,2,3})), List.of(), List.of(), List.of(), "", "");
    }
    private String response(String prompt) throws Exception {
        return envelope(json.writeValueAsString(new ProviderDraft("参考图还原", prompt, List.of(), List.of(), List.of())));
    }
    private String envelope(String content) {
        try { return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
