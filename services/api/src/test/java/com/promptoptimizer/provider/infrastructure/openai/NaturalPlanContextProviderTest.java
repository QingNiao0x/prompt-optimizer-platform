package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 回放跨阶段协议失败并验证新路径完整传递题答；模拟模型只证明传输与保护，语义另以真实评测验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalPlanContextProviderTest {
    private static final String VERSION = "core-repair-v4";
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String RAW = "给客户写延期通知，只输出正文，日期由我决定，请先确认。";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("fixture");
        properties.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @Test
    void savedFailureStillFailsInsteadOfSilentlyDroppingPlanFields() throws Exception {
        var trace = json.readTree(Path.of("../../docs/testing/evidence/natural-feedback-delivery-2026-11-01/traces/0014.json").toFile());
        String user = trace.path("request").path("messages").get(1).path("content").asText();
        ObjectNode payload = (ObjectNode) json.readTree(user.substring(user.indexOf('{')));
        payload.remove(List.of("taskSources", "taskScope", "inputBoundary"));
        var input = json.treeToValue(payload, NaturalPromptInput.class);
        var response = json.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
        var task = json.treeToValue(response.path("taskUnderstanding"), NaturalTaskUnderstanding.class).resolve(input);
        assertThat(task.review().planImpacts()).hasSize(2);
        assertThatThrownBy(() -> task.review().validateEnhancement(task, response.path("optimizedPrompt").asText()))
                .isInstanceOf(ProviderException.class);
        var retry = json.readTree(Path.of("../../docs/testing/evidence/natural-feedback-delivery-2026-11-01/traces/0015.json").toFile());
        assertThatThrownBy(() -> json.readTree(retry.path("response").path("choices").get(0).path("message").path("content").asText()))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void oldInstructionsRemainFrozenAndNewStagesHaveSeparateContracts() {
        assertThat(NaturalPromptInstructions.enhancementInstruction("core-repair-v3"))
                .isEqualTo(NaturalPromptCoreRepairInstructions.COMPLETION_ENHANCE);
        assertThat(NaturalPromptInstructions.planningInstruction("core-repair-v3"))
                .isEqualTo(NaturalPromptCoreRepairInstructions.PLAN);
        assertThat(NaturalPromptInstructions.enhancementInstruction(VERSION))
                .contains("补充可细化这些内容", "bodyQuotes", "已回答不等于参数已确定")
                .doesNotContain("\"planImpacts\":", "\"review\":", "\"taskUnderstanding\":");
        assertThat(NaturalPromptInstructions.planningInstruction(VERSION))
                .contains("decisionBasis", "决定权条件")
                .doesNotContain("\"bodyQuotes\":", "\"planImpacts\":", "taskUnderstanding.openDecisions");
    }

    @ParameterizedTest
    @ValueSource(strings = {"11月8日交付。", "日期由我稍后确认，目前不承诺具体日期。", "不得填写具体日期。"})
    void answeredStatePreservesActualAnswerWithoutClaimingValuesKnown(String answer) throws Exception {
        var input = input(List.of(new PlanAnswer("date", "新的交付日期是什么？", answer)));
        String body = "请给客户写延期通知，只输出正文；" + answer;
        expect(scope(input), input, true);
        expect(enhancement(input, body), input, false);
        var draft = provider.enhance(input, value -> value);
        assertThat(draft.optimizedPrompt()).isEqualTo(body);
        assertThat(draft.completedStages()).contains(NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    @Test
    void noAnswersDoesNotInventPlanCompletion() throws Exception {
        var input = input(List.of());
        expect(scope(input), input, true);
        expect(enhancement(input, RAW), input, false);
        assertThat(provider.enhance(input, draft -> draft).optimizedPrompt()).isEqualTo(RAW);
        server.verify();
    }

    @Test
    void zeroQuestionPlanUsesOnlyPlanRecord() throws Exception {
        var input = input(List.of());
        ObjectNode body = enhancement(input, RAW);
        body.remove(List.of("optimizedPrompt", "framework"));
        body.put("summary", "现有输入足够。");
        body.putArray("questions");
        var task = (ObjectNode) body.path("task");
        task.remove(List.of("additions", "openDecisions"));
        task.putArray("unaskedDecisions");
        task.path("requirements").forEach(item -> ((ObjectNode)item).remove("bodyQuotes"));
        expect(scope(input), input, true);
        expect(body, input, false);
        assertThat(provider.plan(input, result -> result).questions()).isEmpty();
        server.verify();
    }

    @Test
    void crossStageFieldCannotBeDiscardedToProduceSuccess() throws Exception {
        var input = input(List.of());
        var body = enhancement(input, RAW);
        ((ObjectNode)body.path("task")).putArray("planImpacts");
        expect(scope(input), input, true);
        expect(body, input, false);
        expect(body, input, false);
        assertThatThrownBy(() -> provider.enhance(input, result -> result)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void missingActualAnswerCoverageRemainsFailure() throws Exception {
        var input = input(List.of(new PlanAnswer("date", "新的交付日期是什么？", "日期尚未确定。")));
        var body = enhancement(input, RAW);
        ((com.fasterxml.jackson.databind.node.ArrayNode)body.path("task").path("requirements")).remove(1);
        expect(scope(input), input, true);
        expect(body, input, false);
        expect(body, input, false);
        assertThatThrownBy(() -> provider.enhance(input, result -> result)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void taskAndContextUseSeparateEvidenceAndBothReachBodyGeneration() throws Exception {
        var input = input(List.of());
        var prepared = scope(input);
        ((ObjectNode)prepared.path("actions").get(0)).putArray("detailEvidence").addObject()
                .put("sourceId", "context.customDescription").put("quote", "合成背景");
        ((ObjectNode)prepared.path("response")).putArray("detailEvidence").addObject()
                .put("sourceId", "context.customDescription").put("quote", "合成背景");
        expect(prepared, input, true);
        expect(enhancement(input, RAW), input, false);
        assertThat(provider.enhance(input, draft -> draft).completedStages()).contains(NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    @Test
    void savedMaterialOnlyScopeStillRequiresOriginalDelegation() throws Exception {
        var trace = json.readTree(Path.of("../../docs/testing/evidence/natural-simplification-feedback-2026-11-01/traces/0021.json").toFile());
        String user = trace.path("request").path("messages").get(1).path("content").asText();
        ObjectNode payload = (ObjectNode) json.readTree(user.substring(user.indexOf('{')));
        payload.remove(List.of("taskSources", "taskScope", "inputBoundary"));
        var input = json.treeToValue(payload, NaturalPromptInput.class);
        var saved = json.readValue(trace.path("response").path("choices").get(0).path("message").path("content").asText(), NaturalTaskScope.class);
        assertThatThrownBy(() -> saved.resolveAdvisory(input)).isInstanceOf(NaturalTaskScope.MissingUserAuthority.class);
        assertThat(input.taskSources().stream().filter(source -> source.id().equals("context.customDescription")).findFirst().orElseThrow().kind())
                .isEqualTo(NaturalTaskSource.Kind.PROJECT_CONTEXT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "context", "forged"})
    void missingOrFalseUserAuthorityIsNeverRepairedByAddingRawPromptAutomatically(String defect) throws Exception {
        var input = input(List.of());
        var prepared = scope(input);
        var evidence = ((ObjectNode)prepared.path("actions").get(0)).putArray("userEvidence");
        if (defect.equals("context")) evidence.addObject().put("sourceId", "context.customDescription").put("quote", "合成背景");
        if (defect.equals("forged")) evidence.addObject().put("sourceId", "rawPrompt").put("quote", "用户从未提供的委托");
        expect(prepared, input, true);
        expect(prepared, input, true);
        assertThatThrownBy(() -> provider.enhance(input, result -> result)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void recordedRevisionFailuresIdentifyTheAssistantEntryWithoutAcceptingIt() throws Exception {
        for (int number : List.of(1, 2)) {
            var trace = json.readTree(Path.of("../../docs/testing/evidence/natural-daily-v4-2026-11-01/diagnostic/call-" + number + ".json").toFile());
            String user = trace.path("request").path("messages").get(1).path("content").asText();
            var payload = (ObjectNode)json.readTree(user.substring(user.indexOf('{')));
            payload.remove(List.of("taskSources", "inputBoundary", "planProgress"));
            var input = json.treeToValue(payload, NaturalPromptInput.class);
            var recorded = json.readValue(trace.path("response").path("choices").get(0).path("message").path("content").asText(), NaturalTaskScopeSources.class);
            assertThatThrownBy(() -> recorded.toScope(input))
                    .isInstanceOfSatisfying(NaturalTaskScopeSources.InvalidUserEvidence.class, failure -> {
                        assertThat(failure.field()).isEqualTo("actions[0].userEvidence[1].sourceId");
                        assertThat(failure.repairInstruction()).contains("助手生成内容", "revision.previousPrompt", "即使复述用户原意")
                                .doesNotContain("11月4日", "数据同步问题");
                    });
        }
    }

    @Test
    void repairPinpointsMisclassifiedDraftAndKeepsTheOriginalAuthorityGate() throws Exception {
        var input = input(List.of()).withRevision(new NaturalPromptRevision("助手提供的旧稿", List.of(), "表达紧凑些"));
        var invalid = scope(input);
        ((com.fasterxml.jackson.databind.node.ArrayNode)invalid.path("actions").get(0).path("userEvidence"))
                .addObject().put("sourceId", "revision.previousPrompt").put("quote", input.revision().previousPrompt());
        expect(invalid, input, true);
        var corrected = scope(input);
        ((com.fasterxml.jackson.databind.node.ArrayNode)corrected.path("actions").get(0).path("detailEvidence"))
                .addObject().put("sourceId", "revision.previousPrompt").put("quote", input.revision().previousPrompt());
        expect(corrected, input, true, "actions[0].userEvidence[1].sourceId");
        var result = enhancement(input, RAW);
        addRequirement((com.fasterxml.jackson.databind.node.ArrayNode)result.path("task").path("requirements"),
                "revision.feedback", input.revision().feedback(), RAW, "USER_CONFIRMATION");
        expect(result, input, false);
        assertThat(provider.enhance(input, draft -> draft).completedStages()).contains(NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"context.customDescription", "untrusted-secret-text"})
    void invalidSourceDiagnosticsDoNotEchoModelSuppliedValues(String sourceId) {
        var input = input(List.of());
        var valid = new NaturalTaskScope.Evidence("rawPrompt", RAW);
        var bad = new NaturalTaskScope.Evidence(sourceId, "不应输出的引文");
        var scope = new NaturalTaskScopeSources(List.of(new NaturalTaskScopeSources.Action("写通知", NaturalTaskScope.Timing.CURRENT,
                "", List.of(valid, bad), List.of())), new NaturalTaskScopeSources.Response(
                NaturalTaskScope.ResponseBasis.EXPLICIT, "通知", List.of(valid), List.of()));
        assertThatThrownBy(() -> scope.toScope(input))
                .isInstanceOfSatisfying(NaturalTaskScopeSources.InvalidUserEvidence.class, failure -> {
                    assertThat(failure.field()).isEqualTo("actions[0].userEvidence[1].sourceId");
                    assertThat(failure.repairInstruction()).doesNotContain(sourceId, "不应输出的引文");
                });
    }

    private NaturalPromptInput input(List<PlanAnswer> answers) {
        return new NaturalPromptInput(RAW, new ContextSnapshot("合成背景", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "fixture"), answers, List.of(), List.of(), null).withStrategyVersion(VERSION);
    }

    private ObjectNode scope(NaturalPromptInput input) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", "写通知", "timing", "CURRENT", "condition", "", "userEvidence", evidence, "detailEvidence", List.of())),
                "response", Map.of("basis", "EXPLICIT", "description", "通知正文", "userEvidence", evidence, "detailEvidence", List.of())));
    }

    private ObjectNode enhancement(NaturalPromptInput input, String body) {
        var root = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        var task = root.putObject("task").put("goal", "写通知").put("deliverable", "通知正文").put("inputReadiness", "SUFFICIENT");
        var requirements = task.putArray("requirements");
        addRequirement(requirements, "rawPrompt", input.rawPrompt(), body, "GOAL");
        for (int i = 0; i < input.actualAnswers().size(); i++) {
            addRequirement(requirements, "actualAnswers." + i, input.actualAnswers().get(i).answer(), body, "USER_CONFIRMATION");
        }
        task.putArray("additions");
        task.putArray("openDecisions");
        return root;
    }

    private void addRequirement(com.fasterxml.jackson.databind.node.ArrayNode target, String source, String quote, String body, String dimension) {
        var item = target.addObject().put("text", quote);
        item.putArray("dimensions").add(dimension);
        item.putArray("evidence").addObject().put("sourceId", source).put("quote", quote);
        item.putArray("bodyQuotes").add(body);
    }

    /** 同一来源与题答逐字进入两个阶段；交互状态不能替代实际回答，也不额外调用模型判断已答。 */
    private void expect(ObjectNode content, NaturalPromptInput input, boolean preparing) throws Exception {
        expect(content, input, preparing, null);
    }

    /** 修复调用必须携带安全的具体字段反馈；正常调用不额外添加检查负担。 */
    private void expect(ObjectNode content, NaturalPromptInput input, boolean preparing, String repairField) throws Exception {
        String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest)request).getBodyAsString());
            if (repairField != null) {
                assertThat(sent.path("messages").get(0).path("content").asText()).contains(repairField, "助手生成内容");
            }
            String user = sent.path("messages").get(1).path("content").asText();
            JsonNode payload = json.readTree(user.substring(user.indexOf('{')));
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
            assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
            assertThat(payload.path("planProgress").path("state").asText())
                    .isEqualTo(input.actualAnswers().isEmpty() ? "NO_BOUND_ANSWERS" : "ANSWERS_RECEIVED");
            assertThat(payload.path("planProgress").path("answeredQuestionIds"))
                    .isEqualTo(json.valueToTree(input.actualAnswers().stream().map(PlanAnswer::questionId).toList()));
            assertThat(payload.has("taskScope")).isEqualTo(!preparing);
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
