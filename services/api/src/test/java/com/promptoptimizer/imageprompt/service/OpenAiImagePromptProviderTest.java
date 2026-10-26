package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.*;
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
    @Test void analyzeAcceptsOnlyEvidenceAndRecordsItsOwnOperation() throws Exception {
        Call original = call();
        Call analyze = new Call(Stage.ANALYZE, original.input(), original.images(), List.of(), List.of(), List.of(), "", "", List.of(), 1);
        var draft = new ProviderDraft("素材观察", "", List.of(new Observation(asset, "主体", "合成鸟", Visibility.OBSERVED)), List.of(), List.of());
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(envelope(json.writeValueAsString(draft)), MediaType.APPLICATION_JSON));
        var result = provider.invoke(analyze);
        assertThat(result.attempts()).isEqualTo(1); assertThat(result.draft().optimizedPrompt()).isEmpty();
        assertThat(calls).singleElement().satisfies(call -> assertThat(call.operation()).isEqualTo("image.analyze")); server.verify();
        assertThatThrownBy(() -> provider.validate(new ProviderDraft("素材", "提前生成提示词", List.of(), List.of(), List.of()), analyze))
                .isInstanceOf(ProviderException.class);
    }
    @Test void sharedBudgetCanDisableEvenProtocolRepair() {
        Call original = call();
        Call bounded = new Call(original.stage(), original.input(), original.images(), List.of(), List.of(), List.of(), "", "", List.of(), 1);
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(envelope("{}"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.invoke(bounded)).isInstanceOf(ProviderException.class); server.verify();
        assertThat(calls).singleElement().satisfies(call -> assertThat(call.willRetry()).isFalse());
    }
    @Test void sourceAnalysisCannotReceiveCreativeInputWhileGenerationKeepsIt() throws Exception {
        Call original = call(); UUID material = UUID.randomUUID();
        var relation = new ReplacementRelation("title", RelationOperation.REPLACE, material, asset, "源标题", null, null,
                "季度指标·2046", "RELATION_SENTINEL_3059", List.of("文字"), "成品标题");
        var input = new Input(Mode.CUSTOM_CREATION, "fixture", List.of(), "CREATION_SENTINEL_7103", "季度指标·2046",
                "CONTEXT_SENTINEL_6284", Language.ZH, "TOOL_SENTINEL_6927", OutputType.PPT,
                List.of(new MaterialRef(material, Track.REFERENCE, ImageRole.LAYOUT, List.of(new UnitSelection(asset, List.of())))), List.of(relation));
        var source = new MaterialSource(material, "source.pptx", Kind.PPTX, Track.REFERENCE, ImageRole.LAYOUT,
                List.of(new SourceUnit(asset, 2, null, "Q4 DEMO METRICS\nItem Units Score\nALPHA 24 8.5", List.of())), List.of());
        var question = new Question("q1", QuestionType.TEXT, "QUESTION_SENTINEL_2735", List.of(), true);
        var answer = new Answer("q1", List.of(), "ANSWER_SENTINEL_1837");
        var analyzed = new ProviderDraft("源页", "", List.of(new Observation(asset, "文字", "Q4 DEMO METRICS", Visibility.OBSERVED)), List.of(), List.of());
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo(ImagePromptInstructions.ANALYZE_SYSTEM);
            String text = body.path("messages").get(1).path("content").get(0).path("text").asText();
            var payload = json.readTree(text);
            var fields = new ArrayList<String>(); payload.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("stage", "imageSources", "materialSources", "analysisBudget", "batchCoverage");
            assertThat(payload.path("batchCoverage").path("taskSelectedUnitCount").asInt()).isEqualTo(1);
            assertThat(payload.path("batchCoverage").path("currentBatchUnitCount").asInt()).isEqualTo(1);
            assertThat(text).contains("Q4 DEMO METRICS", "ALPHA 24 8.5").doesNotContain("CREATION_SENTINEL_7103", "季度指标·2046", "CONTEXT_SENTINEL_6284",
                    "TOOL_SENTINEL_6927", "RELATION_SENTINEL_3059", "QUESTION_SENTINEL_2735", "ANSWER_SENTINEL_1837", "PREVIOUS_SENTINEL_7483", "REVISION_SENTINEL_3817");
        }).andRespond(withSuccess(envelope(json.writeValueAsString(analyzed)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            var payload = json.readTree(body.path("messages").get(1).path("content").get(0).path("text").asText());
            assertThat(payload.path("input").path("relations").get(0).path("instruction").asText()).isEqualTo("RELATION_SENTINEL_3059");
            assertThat(payload.path("input").path("exactText").asText()).isEqualTo("季度指标·2046");
            assertThat(payload.path("input").path("context").asText()).isEqualTo("CONTEXT_SENTINEL_6284");
            assertThat(payload.path("answers").get(0).path("customText").asText()).isEqualTo("ANSWER_SENTINEL_1837");
        }).andRespond(withSuccess(response("为指定标题制作PPT。"), MediaType.APPLICATION_JSON));
        provider.invoke(new Call(Stage.ANALYZE, input, original.images(), List.of(), List.of(question), List.of(answer),
                "PREVIOUS_SENTINEL_7483", "REVISION_SENTINEL_3817", List.of(source), 1));
        provider.invoke(new Call(Stage.GENERATE, input, List.of(), analyzed.analysis(), List.of(question), List.of(answer), "", "", List.of(source), 1));
        server.verify();
    }

    @Test void taskSelectionAndEachBoundedBatchHaveIndependentCoverageMetadata() throws Exception {
        UUID material = UUID.randomUUID();
        var units = java.util.stream.IntStream.range(0, 4).mapToObj(index -> new SourceUnit(UUID.randomUUID(), null, index * 1000L, "", List.of())).toList();
        var input = new Input(Mode.CUSTOM_CREATION, "fixture", List.of(), "创作视频", "", "", Language.ZH, "通用", OutputType.VIDEO,
                List.of(new MaterialRef(material, Track.REFERENCE, ImageRole.GENERAL, units.stream().map(unit -> new UnitSelection(unit.id(), List.of())).toList())), List.of());
        var images = units.stream().map(unit -> new ImageData(new ImageSource(unit.id(), "frame.png", "hash", ImageRole.GENERAL, 1, 1,
                Instant.now().plusSeconds(3600)), "image/png", new byte[]{1})).toList();
        for (int expected : List.of(3, 1)) {
            server.expect(requestTo(ENDPOINT)).andExpect(request -> {
                var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                var payload = json.readTree(body.path("messages").get(1).path("content").get(0).path("text").asText());
                var coverage = payload.path("batchCoverage");
                assertThat(coverage.path("scope").asText()).isEqualTo("CURRENT_BATCH");
                assertThat(coverage.path("taskSelectedUnitCount").asInt()).isEqualTo(4);
                assertThat(coverage.path("currentBatchUnitCount").asInt()).isEqualTo(expected);
                assertThat(coverage.path("sources").get(0).path("taskSelectedUnitCount").asInt()).isEqualTo(4);
                assertThat(coverage.path("sources").get(0).path("currentBatchUnitIds")).hasSize(expected);
                assertThat(ImagePromptInstructions.ANALYZE_SYSTEM).contains("本批数量少于任务选择不代表任务漏页或漏帧", "不能宣称其他批次已经识别");
            }).andRespond(withSuccess(envelope(json.writeValueAsString(new ProviderDraft("本批来源", "", List.of(), List.of(), List.of()))), MediaType.APPLICATION_JSON));
        }
        provider.invoke(new Call(Stage.ANALYZE, input, images.subList(0, 3), List.of(), List.of(), List.of(), "", "",
                List.of(new MaterialSource(material, "clip.mp4", Kind.VIDEO, Track.REFERENCE, ImageRole.GENERAL, units.subList(0, 3), List.of("任务选择4帧"))), 1));
        provider.invoke(new Call(Stage.ANALYZE, input, images.subList(3, 4), List.of(), List.of(), List.of(), "", "",
                List.of(new MaterialSource(material, "clip.mp4", Kind.VIDEO, Track.REFERENCE, ImageRole.GENERAL, units.subList(3, 4), List.of("任务选择4帧"))), 1));
        server.verify();
    }

    @Test void typedPrivacyMasksReachSourceAnalysisWithoutBanningRealDarkElements() throws Exception {
        Call original = call();
        var source = new MaterialSource(UUID.randomUUID(), "masked.png", Kind.IMAGE, Track.REFERENCE, ImageRole.LAYOUT,
                List.of(new SourceUnit(asset, null, null, "", List.of(new Mask(0.1, 0.1, 0.2, 0.2)))), List.of());
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            var payload = json.readTree(body.path("messages").get(1).path("content").get(0).path("text").asText());
            assertThat(payload.path("materialSources").get(0).path("units").get(0).path("masks").get(0).path("width").asDouble()).isEqualTo(0.2);
            assertThat(ImagePromptInstructions.ANALYZE_SYSTEM).contains("平台隐私遮挡区域", "不把遮挡痕迹当作成品元素", "未遮挡区域中真实存在的深色或黑色元素仍可正常记录");
            assertThat(ImagePromptInstructions.SYSTEM).contains("不把遮挡矩形或痕迹迁移为成品对象");
        }).andRespond(withSuccess(envelope(json.writeValueAsString(new ProviderDraft("合成源图", "",
                List.of(new Observation(asset, "构图", "未遮挡的下方原有黑色横线", Visibility.OBSERVED)), List.of(), List.of()))), MediaType.APPLICATION_JSON));
        var result = provider.invoke(new Call(Stage.ANALYZE, original.input(), original.images(), List.of(), List.of(), List.of(), "", "", List.of(source), 1));
        assertThat(result.draft().analysis()).singleElement().satisfies(observation -> assertThat(observation.description()).contains("原有黑色横线"));
        server.verify();
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
