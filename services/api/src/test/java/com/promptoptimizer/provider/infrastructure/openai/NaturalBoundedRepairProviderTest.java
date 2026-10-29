package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 离线回放已闭批失败并检查48的第二attempt数据边界；所有HTTP均为本地mock，不调用真实模型。
 * 原件回放证明拒绝与诊断位置，不把手工响应或通过协议当成真实作品质量收益。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalBoundedRepairProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String LEGACY_REPAIR = "\n上次响应未满足协议或必要保护。请重新按完整输入生成合法JSON并保留全部要求；每个字段只出现一次，不添加额外内容。";
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> rawRequests = new ArrayList<>();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var settings = new OpenAiCompatibleProperties();
        settings.setEndpoint(URI.create(ENDPOINT));
        settings.setApiKey("fixture");
        settings.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, settings, (PlatformModelCatalog) null);
    }

    @Test
    void firstWriterAndPlanRemainFrozenAndDefaultIsStill31() throws Exception {
        assertThat(NaturalPromptInstructions.enhancementInstruction("48")).isEqualTo(NaturalPromptInstructions.enhancementInstruction("47"));
        assertThat(NaturalPromptInstructions.planningInstruction("48")).isEqualTo(NaturalPromptInstructions.planningInstruction("47"));
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
        // 仅显式捕获任务使用已闭批Q01原件触发产品首稿构造；默认测试不增加HTTP夹具流程。
        if (!System.getProperty("natural.writer.export", "").isBlank()) {
            configureCapturedDeepSeekRoute();
            var original = fixture("Q01");
            var input = fixtureInput(original, true);
            expect(json.writeValueAsString(original.path("scopeResponse")), request -> {});
            expect(original.path("responseContent").asText(), request -> {});
            provider.enhance(input, value -> checked(value, input));
            server.verify();
            exportWriterRequest("Q01", "effective47/traces/008.json", original);
        }
    }

    @Test
    void closedOriginalsStillFailAtTheSameGuardsAndAllBadQuotePositionsAreReported() throws Exception {
        for (String number : List.of("008", "009", "013", "014")) {
            JsonNode fixture = fixture(number);
            var input = fixtureInput(fixture);
            var body = json.readTree(fixture.path("responseContent").asText());
            var task = json.treeToValue(body.path("taskUnderstanding"), NaturalTaskUnderstanding.class);
            var issues = NaturalPromptRepairContext.inspect(json, body, input, false);
            if (number.equals("008")) {
                assertThatThrownBy(() -> task.resolve(input)).hasMessage("任务理解引用不属于当前可用来源");
                assertThat(issues).extracting(NaturalPromptRepairContext.Issue::code).containsExactly("SOURCE_QUOTE_MISMATCH");
                assertThat(issues.getFirst().path()).isEqualTo("/taskUnderstanding/requirements/4/evidence/0/quote");
            } else if (number.equals("014")) {
                var resolved = task.resolve(input);
                resolved.review().validateEnhancement(resolved, body.path("optimizedPrompt").asText());
                assertThat(issues).isEmpty();
            } else {
                var resolved = task.resolve(input);
                assertThatThrownBy(() -> resolved.review().validateEnhancement(resolved, body.path("optimizedPrompt").asText()))
                        .hasMessage("正文覆盖记录越界、重复或缺少对应原句");
                var expected = number.equals("009") ? List.of(12, 13, 14, 15, 16) : List.of(6);
                assertThat(issues).extracting(NaturalPromptRepairContext.Issue::path).containsExactlyElementsOf(expected.stream()
                        .map(index -> "/taskUnderstanding/review/requirementCoverage/" + index + "/quotes/0").toList());
                assertThat(issues).allMatch(issue -> issue.code().equals("BODY_QUOTE_MISSING"));
            }
        }
    }

    @Test
    void twoBadClosedBodiesStillFailWithoutAnAdditionalAttempt() throws Exception {
        configureCapturedDeepSeekRoute();
        var first = fixture("008");
        var second = fixture("009");
        var input = fixtureInput(first, true);
        expect(json.writeValueAsString(first.path("scopeResponse")), request -> {});
        expect(first.path("responseContent").asText(), request -> assertThat(payload(request).has("protocolRepair")).isFalse());
        expect(second.path("responseContent").asText(), request -> {
            var repair = payload(request).path("protocolRepair");
            assertThat(repair.path("invalidResponse").asText()).isEqualTo(first.path("responseContent").asText());
            assertThat(repair.path("issues").get(0).path("path").asText()).isEqualTo("/taskUnderstanding/requirements/4/evidence/0/quote");
            assertThat(request.path("messages").get(0).path("content").asText())
                    .isEqualTo(NaturalPromptBoundedRepairInstructions.ENHANCE + LEGACY_REPAIR + NaturalPromptBoundedRepairInstructions.REPAIR);
        });
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(3);
        server.verify();
        exportRepairRequest("U02-from-008", "008", "009", first, second);
        exportWriterRequest("U02", "completion47/traces/008.json", first);
    }

    @Test
    void closedSuccessfulRepairStillPassesTheOriginalSourceAndBodyGuards() throws Exception {
        configureCapturedDeepSeekRoute();
        var first = fixture("013");
        var second = fixture("014");
        var input = fixtureInput(first, true);
        expect(json.writeValueAsString(first.path("scopeResponse")), request -> {});
        expect(first.path("responseContent").asText(), request -> {});
        expect(second.path("responseContent").asText(), request -> {
            var repair = payload(request).path("protocolRepair");
            assertThat(repair.path("invalidResponse").asText()).isEqualTo(first.path("responseContent").asText());
            assertThat(repair.path("issues").size()).isEqualTo(1);
            assertThat(repair.path("issues").get(0).path("path").asText()).endsWith("/requirementCoverage/6/quotes/0");
        });
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(json.readTree(second.path("responseContent").asText()).path("optimizedPrompt").asText());
        assertThat(draft.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
        exportRepairRequest("U03-from-013", "013", "014", first, second);
        exportWriterRequest("U03", "completion47/traces/013.json", first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"31", "45", "46", "47"})
    void legacySecondRequestsRemainByteCompatible(String version) throws Exception {
        var input = input("请解释事务。", List.of(), List.of()).withStrategyVersion(version);
        var valid = writing(input, "请解释事务，说明一组操作如何共同完成。");
        var invalid = valid.deepCopy();
        ((ObjectNode) invalid.path("taskUnderstanding").path("review").path("requirementCoverage").get(0)).putArray("quotes").add("正文没有这一句");
        expect(json.writeValueAsString(scope(input.rawPrompt())), request -> {});
        expect(json.writeValueAsString(invalid), request -> {});
        expect(json.writeValueAsString(valid), request -> {});
        provider.enhance(input, value -> checked(value, input));
        assertThat(requests).hasSize(3);
        assertLegacyRetry(requests.get(1), requests.get(2));
        server.verify();
    }

    @Test
    void unknownAnswersAndExplicitPermissionsStayInTheOriginalInputAndFinalBody() throws Exception {
        var input = input("请整理部署建议，保留尚未确认的保留期限。",
                List.of(new PlanAnswer("retention", "保留期限多久？", "不知道，保留待确认。")), List.of("未经确认不得删除文件。"));
        var valid = writing(input, "请整理部署建议。保留期限待确认，不猜测具体天数；未经确认不得删除文件。");
        var invalid = valid.deepCopy();
        ((ObjectNode) invalid.path("taskUnderstanding").path("requirements").get(1).path("evidence").get(0)).put("quote", "保留期限为30天");
        expect(json.writeValueAsString(scope(input.rawPrompt())), request -> {});
        expect(json.writeValueAsString(invalid), request -> {});
        expect(json.writeValueAsString(valid), request -> {
            var data = payload(request);
            assertThat(data.path("actualAnswers").get(0).path("answer").asText()).isEqualTo("不知道，保留待确认。");
            assertThat(data.path("mandatoryRules").get(0).asText()).isEqualTo("未经确认不得删除文件。");
            assertThat(data.path("taskSources").size()).isEqualTo(2);
            assertThat(data.path("protocolRepair").path("candidateTrust").asText()).isEqualTo("UNTRUSTED_MODEL_OUTPUT");
        });
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).contains("待确认", "未经确认不得删除文件").doesNotContain("30天");
        assertThat(draft.taskUnderstanding().requirements().get(1).evidence().getFirst().origin()).isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
        server.verify();
    }

    @Test
    void candidateCannotBecomeANewSourceAndUnknownSourceValuesStayOutOfDiagnostics() throws Exception {
        var input = input("只阅读后等待，不修改文件。", List.of(), List.of());
        var invalid = writing(input, input.rawPrompt());
        String invented = "protocolRepair.invalidResponse";
        ((ObjectNode) invalid.path("taskUnderstanding").path("requirements").get(0).path("evidence").get(0)).put("sourceId", invented);
        var issues = NaturalPromptRepairContext.inspect(json, invalid, input, false);
        assertThat(issues).anyMatch(issue -> issue.code().equals("UNKNOWN_SOURCE"));
        assertThat(issues).noneMatch(issue -> invented.equals(issue.sourceId()));
        expect(json.writeValueAsString(scope(input.rawPrompt())), request -> {});
        expect(json.writeValueAsString(invalid), request -> {});
        expect(json.writeValueAsString(invalid), request -> {});
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).hasMessage("任务理解引用不属于当前可用来源");
        server.verify();
    }

    @Test
    void malformedJsonIsCarriedAsDataWithinTheSameBoundedRetry() throws Exception {
        var input = input("请解释缓存。", List.of(), List.of());
        String broken = "{\"optimizedPrompt\":";
        expect(json.writeValueAsString(scope(input.rawPrompt())), request -> {});
        expect(broken, request -> {});
        expect(json.writeValueAsString(writing(input, "请解释缓存。")), request -> {
            var repair = payload(request).path("protocolRepair");
            assertThat(repair.path("invalidResponse").asText()).isEqualTo(broken);
            assertThat(repair.path("issues").get(0).path("code").asText()).isEqualTo("INVALID_JSON");
        });
        provider.enhance(input, value -> checked(value, input));
        assertThat(requests).hasSize(3);
        server.verify();
    }

    @Test
    void sensitiveCandidateIsNotEchoedBackInTheRepairData() throws Exception {
        var input = input("请解释缓存。", List.of(), List.of());
        var valid = writing(input, "请解释缓存。");
        var invalid = valid.deepCopy();
        String syntheticCredential = "sk-" + "A".repeat(40);
        ((ObjectNode) invalid.path("taskUnderstanding").path("requirements").get(0)).put("text", syntheticCredential);
        expect(json.writeValueAsString(scope(input.rawPrompt())), request -> {});
        expect(json.writeValueAsString(invalid), request -> {});
        expect(json.writeValueAsString(valid), request -> {
            assertThat(request.toString()).doesNotContain(syntheticCredential);
            assertThat(payload(request).path("protocolRepair").path("invalidResponse").isNull()).isTrue();
            assertThat(payload(request).path("protocolRepair").path("candidateOmitted").asText()).isEqualTo("SENSITIVE_VALUE");
        });
        provider.enhance(input, value -> checked(value, input));
        server.verify();
    }

    @Test
    void planRetryKeepsTheExistingProtocolAndDoesNotReceiveCandidateData() throws Exception {
        var input = input("请解释缓存。", List.of(), List.of());
        var plan = json.createObjectNode().put("summary", "按原任务执行，没有需要用户决定的重要未知。");
        plan.putArray("questions");
        plan.set("taskUnderstanding", json.valueToTree(task(input, null)));
        var invalid = plan.deepCopy();
        ((ObjectNode) invalid.path("taskUnderstanding").path("requirements").get(0).path("evidence").get(0)).put("quote", "不存在的原句");
        expect(json.writeValueAsString(scope(input.rawPrompt())), request -> {});
        expect(json.writeValueAsString(invalid), request -> {});
        expect(json.writeValueAsString(plan), request -> {});
        assertThat(provider.plan(input, value -> {
            var resolved = value.taskUnderstanding().resolve(input);
            resolved.review().validatePlan(Set.of());
            return value;
        }).questions()).isEmpty();
        assertLegacyRetry(requests.get(1), requests.get(2));
        server.verify();
    }

    /** 只替换测试传输模型；原始来源、上下文及响应逐字来自安全闭批夹具。 */
    private NaturalPromptInput fixtureInput(JsonNode fixture) throws Exception {
        return fixtureInput(fixture, false);
    }

    /** 捕获机制请求时保留原公开模型ID；纯诊断测试可继续使用默认fixture传输模型。 */
    private NaturalPromptInput fixtureInput(JsonNode fixture, boolean originalModel) throws Exception {
        var original = json.treeToValue(fixture.path("input"), NaturalPromptInput.class);
        return new NaturalPromptInput(original.rawPrompt(), original.context(), original.actualAnswers(), original.conversation(),
                original.mandatoryRules(), originalModel ? original.modelId() : null, original.planningContext(), "48");
    }

    /** 用受控路由驱动产品实际请求构造；认证值为合成fixture且只存在于被mock消费的header。 */
    private void configureCapturedDeepSeekRoute() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var route = new OpenAiCompatibleRouteProperties();
        route.setEndpoint(URI.create(ENDPOINT));
        route.setApiKey("fixture");
        route.setProviderName("deepseek");
        route.setModel("deepseek-flash");
        var settings = new OpenAiCompatibleProperties();
        settings.setMultiProviderEnabled(true);
        settings.setDefaultProvider("deepseek");
        settings.setProviders(Map.of("deepseek", route));
        settings.setTemperature(0.2);
        settings.setMaxTokens(8192);
        settings.setJsonResponseFormatEnabled(true);
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, settings, (PlatformModelCatalog) null);
    }

    /**
     * 仅显式系统属性允许安全导出Mock收到的实际body，不保存header、URL、环境或任何认证配置。
     * CREATE_NEW防止覆盖已冻结入口；输出只允许本轮evidence，默认测试不产生额外文件。
     */
    private void exportRepairRequest(String name, String firstNumber, String controlNumber, JsonNode first, JsonNode control) throws Exception {
        String configured = System.getProperty("natural.repair.export", "");
        if (configured.isBlank()) return;
        Path repository = Path.of("../..").toAbsolutePath().normalize();
        Path allowed = repository.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28");
        Path output = Path.of(configured).toAbsolutePath().normalize();
        assertThat(output.startsWith(allowed)).isTrue();
        Files.createDirectories(output);
        JsonNode request = requests.get(2);
        assertThat(request.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(request.has("headers") || request.has("apiKey") || request.has("endpoint")).isFalse();
        String raw = rawRequests.get(2);
        Files.writeString(output.resolve(name + ".request.json"), raw, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        ObjectNode metadata = json.createObjectNode();
        metadata.put("status", "CAPTURED_FROM_PRODUCT_MOCK_HTTP_BODY_NOT_EXECUTED");
        metadata.put("construction", "Actual OpenAiCompatibleNaturalPromptProvider body captured by MockClientHttpRequest at existing writer attempt 2; controlled DeepSeek route, synthetic authentication and Mock endpoint; no headers or environment exported.");
        metadata.put("requestSha256", sha256(raw));
        metadata.put("userMessageSha256", sha256(request.path("messages").get(1).path("content").asText()));
        metadata.put("firstInvalidClosedTrace", "completion47/traces/" + firstNumber + ".json");
        metadata.put("firstInvalidClosedTraceSha256", first.path("sourceTraceSha256").asText());
        metadata.put("legacyRepairControlTrace", "completion47/traces/" + controlNumber + ".json");
        metadata.put("legacyRepairControlTraceSha256", control.path("sourceTraceSha256").asText());
        metadata.put("sourceInputSha256", sha256(json.writeValueAsString(first.path("input"))));
        metadata.put("firstWriterSystemSha256", sha256(requests.get(1).path("messages").get(0).path("content").asText()));
        metadata.put("firstWriterEqualsFrozen47", requests.get(1).path("messages").get(0).path("content").asText().equals(NaturalPromptEffectiveRequestInstructions.ENHANCE));
        metadata.put("realModelCalls", 0);
        Files.writeString(output.resolve(name + ".provenance.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(metadata), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** 对实际HTTP正文和输入使用UTF-8摘要，便于后续冻结逐字核对。 */
    private String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** 仅导出产品构造的首稿完整body；后续独立profile不得改写本请求的system、任务或资料。 */
    private void exportWriterRequest(String caseId, String sourceTrace, JsonNode original) throws Exception {
        String configured = System.getProperty("natural.writer.export", "");
        if (configured.isBlank()) return;
        Path repository = Path.of("../..").toAbsolutePath().normalize();
        Path allowed = repository.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28");
        Path output = Path.of(configured).toAbsolutePath().normalize();
        assertThat(output.startsWith(allowed)).isTrue();
        Files.createDirectories(output);
        JsonNode request = requests.get(1);
        assertThat(payload(request).has("protocolRepair")).isFalse();
        assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptEffectiveRequestInstructions.ENHANCE);
        String raw = rawRequests.get(1);
        Files.writeString(output.resolve(caseId + ".json"), raw, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        ObjectNode metadata = json.createObjectNode();
        metadata.put("status", "CAPTURED_PRODUCT_FIRST_BODY_NOT_EXECUTED");
        metadata.put("construction", "Actual Provider first-writer HTTP body captured by MockClientHttpRequest; closed original input and scope replayed with strategyVersion48; no endpoint, authentication or environment exported.");
        metadata.put("sourceClosedTrace", sourceTrace);
        metadata.put("sourceClosedTraceSha256", original.path("sourceTraceSha256").asText());
        metadata.put("sourceInputSha256", sha256(json.writeValueAsString(original.path("input"))));
        metadata.put("requestSha256", sha256(raw));
        metadata.put("systemSha256", sha256(request.path("messages").get(0).path("content").asText()));
        metadata.put("userMessageSha256", sha256(request.path("messages").get(1).path("content").asText()));
        metadata.put("profileBoundary", "Subsequent independent OFF/ON experiment may change only max_tokens and thinking/effort parameters; preserve model, messages, temperature, response_format and stream.");
        metadata.put("realModelCalls", 0);
        Files.writeString(output.resolve(caseId + ".provenance.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(metadata), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private JsonNode fixture(String number) throws Exception {
        try (var stream = getClass().getResourceAsStream("/natural-bounded-repair/trace-" + number + ".json")) {
            assertThat(stream).isNotNull();
            return json.readTree(stream);
        }
    }

    private NaturalPromptInput input(String raw, List<PlanAnswer> answers, List<String> permissions) {
        return new NaturalPromptInput(raw, null, answers, List.of(), permissions, null).withStrategyVersion("48");
    }

    private NaturalPromptDraft checked(NaturalPromptDraft draft, NaturalPromptInput input) {
        var task = draft.taskUnderstanding().resolve(input);
        task.review().validateEnhancement(task, draft.optimizedPrompt().strip());
        return draft;
    }

    private ObjectNode scope(String raw) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", raw));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", raw, "timing", "CURRENT", "condition", "", "evidence", evidence)),
                "response", Map.of("basis", "EXPLICIT", "description", "原定回应", "evidence", evidence)));
    }

    private ObjectNode writing(NaturalPromptInput input, String body) {
        var response = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        response.set("taskUnderstanding", json.valueToTree(task(input, body)));
        return response;
    }

    private NaturalTaskUnderstanding task(NaturalPromptInput input, String body) {
        var requirements = new ArrayList<NaturalTaskUnderstanding.Requirement>();
        var coverage = new ArrayList<NaturalTaskReview.Coverage>();
        for (var source : input.taskSources()) {
            requirements.add(new NaturalTaskUnderstanding.Requirement(source.text(),
                    List.of(new NaturalTaskUnderstanding.Evidence(source.id(), source.text())), List.of(NaturalTaskUnderstanding.Dimension.GOAL)));
            if (body != null) coverage.add(new NaturalTaskReview.Coverage(coverage.size(), List.of(body)));
        }
        return new NaturalTaskUnderstanding(input.rawPrompt(), "", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, coverage, List.of(), List.of()));
    }

    /** 比较完整请求，仅允许旧有的system固定修复后缀不同，防止旧历史被新数据形状静默改变。 */
    private void assertLegacyRetry(JsonNode first, JsonNode second) {
        String firstSystem = first.path("messages").get(0).path("content").asText();
        assertThat(second.path("messages").get(0).path("content").asText()).isEqualTo(firstSystem + LEGACY_REPAIR);
        assertThat(payload(second).has("protocolRepair")).isFalse();
        ObjectNode restored = second.deepCopy();
        ((ObjectNode) restored.path("messages").get(0)).put("content", firstSystem);
        assertThat(restored.toString()).isEqualTo(first.toString());
    }

    private JsonNode payload(JsonNode request) {
        try {
            String user = request.path("messages").get(1).path("content").asText();
            return json.readTree(user.substring(user.indexOf('{')));
        } catch (Exception failure) { throw new AssertionError("Mock request payload is invalid", failure); }
    }

    private void expect(String content, Consumer<JsonNode> check) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            String raw = ((MockClientHttpRequest) request).getBodyAsString();
            var requestJson = json.readTree(raw);
            requests.add(requestJson);
            rawRequests.add(raw);
            check.accept(requestJson);
        }).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
