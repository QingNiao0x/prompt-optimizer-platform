package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.DependencyItem;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.domain.TechnologyStackItem;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 用合成多文件输入复现材料错引，验证一次修复的定位、阶段隔离和失败封闭；不冒充真实模型质量评测。 */
class NaturalScopeEvidenceRepairTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String RAW = "检查 @src/catalog 的新增和导入数量校验，修复允许负数的问题，只返回修改的业务文件。";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("fixture");
        properties.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog)null);
    }

    @ParameterizedTest
    @CsvSource({"core-repair-v4,false", "core-repair-v4,true", "internal-design-v1,false", "internal-design-v1,true"})
    void repairsAllMaterialQuotesInOneAttemptWithoutChangingInputOrMixingPlanProtocol(String version, boolean plan) throws Exception {
        var input = input().withStrategyVersion(version);
        var invalid = scope(input, true);
        var valid = scope(input, false);
        expect(invalid, input, null, true);
        expect(valid, input, invalid, true);
        var result = result(input, plan);
        expect(result, input, null, false);
        var calls = new ArrayList<ModelCallObservation>();
        try (var audit = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(UUID.randomUUID(), (tenant, call) -> calls.add(call)))) {
            if (plan) assertThat(provider.plan(input, value -> value).questions()).isEmpty();
            else assertThat(provider.enhance(input, value -> value).completedStages()).contains(NaturalProcessing.Stage.BODY_GENERATION);
        }
        assertThat(calls).hasSize(3);
        assertThat(calls.getFirst().validationReason()).isEqualTo("SOURCE_EVIDENCE_INVALID");
        assertThat(calls.getFirst().validationField()).isEqualTo("actions[0].detailEvidence[0].quote");
        assertThat(calls.get(1).successful()).isTrue();
        server.verify();
    }

    @Test
    void repeatedInvalidQuoteStillFailsAfterOneRepairWithoutGeneratingOrReturningRawSource() throws Exception {
        var input = input();
        var invalid = scope(input, true);
        expect(invalid, input, null, true);
        expect(invalid, input, invalid, true);
        assertThatThrownBy(() -> provider.enhance(input, value -> value))
                .isInstanceOfSatisfying(NaturalTaskScopeSources.InvalidEvidence.class,
                        failure -> assertThat(failure.fields()).containsExactly(
                                "actions[0].detailEvidence[0].quote", "actions[0].detailEvidence[1].quote"));
        server.verify();
    }

    @Test
    void nonexistentSourceAndCrossSourceQuoteDoNotBecomeValidThroughRepairMetadata() throws Exception {
        var input = input();
        var invalid = scope(input, false);
        var details = invalid.path("actions").get(0).path("detailEvidence");
        ((ObjectNode)details.get(0)).put("sourceId", "model-supplied-private-id");
        ((ObjectNode)details.get(1)).put("quote", "export const category0 = 0;");
        ((com.fasterxml.jackson.databind.node.ArrayNode)details).addObject()
                .put("sourceId", "context.fileSnippets.2").put("quote", "这段原文不存在");
        assertThatThrownBy(() -> json.treeToValue(invalid, NaturalTaskScopeSources.class).toScope(input))
                .isInstanceOfSatisfying(NaturalTaskScopeSources.InvalidEvidence.class, failure -> {
                    assertThat(failure.fields()).containsExactly("actions[0].detailEvidence[0].sourceId",
                            "actions[0].detailEvidence[1].quote", "actions[0].detailEvidence[2].quote");
                    assertThat(failure.issues()).extracting(NaturalTaskScopeSources.ReferenceIssue::reason)
                            .containsExactly(NaturalTaskScopeSources.ReferenceFailureReason.UNKNOWN_SOURCE_ID,
                                    NaturalTaskScopeSources.ReferenceFailureReason.QUOTE_FOUND_IN_OTHER_SOURCE,
                                    NaturalTaskScopeSources.ReferenceFailureReason.QUOTE_NOT_FOUND);
                    assertThat(failure.issues().get(1).candidateSourceIds()).containsExactly("context.fileSnippets.0");
                    assertThat(failure.repairInstruction()).doesNotContain("model-supplied-private-id", "category0", RAW);
                });
    }

    @Test
    void sensitiveInvalidCandidateIsNotReplayedAndNeverAddedAsASource() throws Exception {
        var input = input();
        var invalid = scope(input, true);
        NaturalTaskScopeSources.InvalidEvidence failure = catchThrowableOfType(
                () -> json.treeToValue(invalid, NaturalTaskScopeSources.class).toScope(input), NaturalTaskScopeSources.InvalidEvidence.class);
        var original = json.valueToTree(input);
        String repaired = NaturalPromptRepairContext.scopeUserMessage(original, "api_key=sk-test-secret-12345678901234567890", failure);
        var payload = json.readTree(repaired.substring(repaired.indexOf('{')));
        assertThat(payload.path("protocolRepair").path("candidateOmitted").asText()).isEqualTo("SENSITIVE_VALUE");
        assertThat(payload.path("protocolRepair").path("invalidResponse").isNull()).isTrue();
        assertThat(payload.path("taskSources")).isEqualTo(original.path("taskSources"));
        assertThat(payload.path("protocolRepair").path("issues").get(0).path("code").asText())
                .isEqualTo("QUOTE_FOUND_IN_OTHER_SOURCE");
        assertThat(payload.path("protocolRepair").path("issues").get(0).path("candidateSourceIds").get(0).asText())
                .isEqualTo("context.fileSnippets.1");
        assertThat(repaired).doesNotContain("sk-test-secret");
    }

    @Test
    void sourceLabelsOnlyExistOnCoreRepairPreparationInput() {
        var input = input();
        var bodyPayload = json.valueToTree(input);
        NaturalPromptPreparationSourceLabels.annotate("core-repair-v3", true, bodyPayload, input);
        NaturalPromptPreparationSourceLabels.annotate("core-repair-v4", false, bodyPayload, input);
        assertThat(bodyPayload.path("context").path("fileSnippets").get(0).has("sourceId")).isFalse();
        assertThat(bodyPayload.path("context").has("directoryTreeSourceIds")).isFalse();
    }

    private NaturalPromptInput input() {
        var files = IntStream.range(0, 22).mapToObj(index -> new FileSnippet("src/catalog/file" + index + ".ts", "typescript",
                "// 合成测试材料，不含业务源码。\n".repeat(240) + "export const category" + index + " = " + index + ";\n",
                "合成模块" + index, false)).toList();
        var context = new ContextSnapshot("合成项目背景", List.of(new TechnologyStackItem("TypeScript", "package.json", 1.0)),
                List.of(new DependencyItem("npm", "vue", "3", "package.json")), List.of("src/catalog"), files,
                List.of(), List.of(), "fixture");
        var planning = new ContextSnapshot("历史项目上下文", List.of(), List.of(), List.of(),
                List.of(new FileSnippet("src/catalog/previous.ts", "typescript", "export const previous = true;", "先前材料", false)),
                List.of(), List.of(), "fixture");
        return new NaturalPromptInput(RAW, context,
                List.of(new PlanAnswer("scope", "允许修改哪些文件？", "只允许修改业务文件，不新增测试文件。")),
                List.of(), List.of(), null, planning).withStrategyVersion("core-repair-v4");
    }

    private ObjectNode scope(NaturalPromptInput input, boolean invalid) {
        var user = List.of(Map.of("sourceId", "rawPrompt", "quote", RAW));
        var details = List.of(Map.of("sourceId", "context.fileSnippets.0", "quote", invalid ? "export const category1 = 1;" : "export const category0 = 0;"),
                Map.of("sourceId", "context.fileSnippets.1", "quote", invalid ? "不存在的材料片段" : "export const category1 = 1;"));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", "修复新增和导入数量校验", "timing", "CURRENT", "condition", "",
                        "userEvidence", user, "detailEvidence", details)),
                "response", Map.of("basis", "EXPLICIT", "description", "修改的业务文件", "userEvidence", user, "detailEvidence", List.of())));
    }

    private ObjectNode result(NaturalPromptInput input, boolean plan) {
        String body = RAW + input.actualAnswers().getFirst().answer();
        var result = json.createObjectNode();
        var task = result.putObject("task").put("goal", "修复数量校验").put("deliverable", "修改的业务文件").put("inputReadiness", "SUFFICIENT");
        var requirements = task.putArray("requirements");
        for (var source : input.taskSources()) {
            if (source.kind() != NaturalTaskSource.Kind.ORIGINAL_REQUEST && source.kind() != NaturalTaskSource.Kind.BOUND_PLAN_ANSWER) continue;
            var item = requirements.addObject().put("text", source.text());
            item.putArray("dimensions").add(source.kind() == NaturalTaskSource.Kind.ORIGINAL_REQUEST ? "GOAL" : "USER_CONFIRMATION");
            item.putArray("evidence").addObject().put("sourceId", source.id()).put("quote", source.text());
            if (!plan) item.putArray("bodyQuotes").add(body);
        }
        if (plan) {
            result.put("summary", "已有修改范围，无需继续确认。");
            result.putArray("questions");
            task.putArray("unaskedDecisions");
        } else {
            result.put("optimizedPrompt", body).put("framework", "APE");
            task.putArray("additions");
            task.putArray("openDecisions");
        }
        return result;
    }

    /** 检查整个实际请求：原始材料与题答不缩水，修复候选只在第二次准备请求的数据区出现。 */
    private void expect(ObjectNode output, NaturalPromptInput input, ObjectNode invalid, boolean preparing) throws Exception {
        String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(output))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest)request).getBodyAsString());
            String user = sent.path("messages").get(1).path("content").asText();
            var payload = json.readTree(user.substring(user.indexOf('{')));
            var context = payload.path("context").deepCopy();
            stripPreparationLabels(context);
            assertThat(context).isEqualTo(json.valueToTree(input.context()));
            var planningContext = payload.path("planningContext").deepCopy();
            stripPreparationLabels(planningContext);
            assertThat(planningContext).isEqualTo(json.valueToTree(input.planningContext()));
            if (preparing) {
                assertThat(payload.path("context").path("customDescriptionSourceId").asText())
                        .isEqualTo("context.customDescription");
                assertThat(payload.path("context").path("fileSnippets").get(0).path("sourceId").asText())
                        .isEqualTo("context.fileSnippets.0");
                assertThat(payload.path("context").path("fileSnippets").get(0).path("summarySourceId").asText())
                        .isEqualTo("context.fileSnippets.0.summary");
                assertThat(payload.path("context").path("technologyStack").get(0).path("sourceId").asText())
                        .isEqualTo("context.technologyStack.0");
                assertThat(payload.path("context").path("dependencies").get(0).path("sourceId").asText())
                        .isEqualTo("context.dependencies.0");
                assertThat(payload.path("context").path("directoryTreeSourceIds").get(0).asText())
                        .isEqualTo("context.directoryTree.0");
                assertThat(payload.path("planningContext").path("fileSnippets").get(0).path("sourceId").asText())
                        .isEqualTo("planningContext.fileSnippets.0");
            } else {
                assertThat(payload.path("context").path("fileSnippets").get(0).has("sourceId")).isFalse();
                assertThat(payload.path("planningContext").path("fileSnippets").get(0).has("sourceId")).isFalse();
            }
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(RAW);
            assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
            assertThat(payload.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
            assertThat(payload.has("taskScope")).isEqualTo(!preparing);
            if (invalid == null) assertThat(payload.has("protocolRepair")).isFalse();
            else {
                String system = sent.path("messages").get(0).path("content").asText();
                assertThat(system).contains("actions[0].detailEvidence[0].quote", "actions[0].detailEvidence[1].quote")
                        .doesNotContain("export const category1 = 1;", "不存在的材料片段", RAW);
                var repair = payload.path("protocolRepair");
                assertThat(repair.path("candidateTrust").asText()).isEqualTo("UNTRUSTED_MODEL_OUTPUT");
                assertThat(json.readTree(repair.path("invalidResponse").asText())).isEqualTo(invalid);
                assertThat(repair.path("issues")).hasSize(2);
                assertThat(repair.path("issues").get(0).path("code").asText()).isEqualTo("QUOTE_FOUND_IN_OTHER_SOURCE");
                assertThat(repair.path("issues").get(0).path("candidateSourceIds").get(0).asText())
                        .isEqualTo("context.fileSnippets.1");
                assertThat(repair.path("issues").get(1).path("code").asText()).isEqualTo("QUOTE_NOT_FOUND");
            }
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void stripPreparationLabels(JsonNode context) {
        if (!(context instanceof ObjectNode snapshot)) return;
        snapshot.remove(List.of("customDescriptionSourceId", "directoryTreeSourceIds"));
        for (String field : List.of("technologyStack", "dependencies", "fileSnippets")) {
            snapshot.path(field).forEach(item -> {
                if (item instanceof ObjectNode object) object.remove(List.of("sourceId", "summarySourceId"));
            });
        }
    }
}
