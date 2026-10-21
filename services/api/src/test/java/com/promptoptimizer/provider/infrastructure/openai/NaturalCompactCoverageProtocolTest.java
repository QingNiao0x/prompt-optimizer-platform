package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 通过真实HTTP适配入口核对第五版紧凑记录，来源、独立答案及正文覆盖保护不能因省略诊断复述而减少。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalCompactCoverageProtocolTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setup() {
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
    void compactEntriesKeepAllSourcesAndQuotesWithoutRepeatingDiagnosticText() throws Exception {
        var input = input();
        String content = response(compact());
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var envelope = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            String user = envelope.path("messages").get(1).path("content").asText();
            var payload = json.readTree(user.substring(user.indexOf('{')));
            assertThat(payload.path("requiredSourceIds")).isEqualTo(json.valueToTree(List.of("rawPrompt", "actualAnswers.0", "actualAnswers.1")));
        }).andRespond(withSuccess(content, MediaType.APPLICATION_JSON));
        var result = provider.enhance(input, draft -> {
            var task = draft.taskUnderstanding();
            task.review().validateEnhancement(task, draft.optimizedPrompt());
            assertThat(task.requirements()).hasSize(3);
            assertThat(task.requirements().stream().flatMap(requirement -> requirement.evidence().stream()))
                    .extracting(NaturalTaskUnderstanding.Evidence::sourceId)
                    .containsExactly("rawPrompt", "actualAnswers.0", "actualAnswers.1");
            assertThat(task.requirements().get(1).evidence().getFirst().origin()).isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
            assertThat(task.requirements().get(2).evidence().getFirst().origin()).isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER);
            assertThat(task.review().requirementCoverage()).extracting(NaturalTaskReview.Coverage::index).containsExactly(0, 1, 2);
            assertThat(task.additions().getFirst().gap()).isEqualTo("还需说明未核实期限只影响对应安排");
            assertThat(task.review().additionCoverage().getFirst().quotes()).containsExactly("期限尚未确认，不替用户确定日期");
            assertThat(input.actualAnswers()).extracting(PlanAnswer::question).containsExactly("交接对象是谁？", "需要通知谁？");
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo("写交接说明，对象为接班同事。期限尚未确认，不替用户确定日期。");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing_quote", "wrong_quote", "wrong_source", "missing_answer", "missing_gap", "invalid_dimensions", "mixed_coverage", "newline_quote"})
    void compactProtocolStillRejectsMissingOrConflictingProtection(String fault) throws Exception {
        var tree = compact();
        var task = (ObjectNode) tree.path("taskUnderstanding");
        var requirement = (ObjectNode) task.path("requirements").get(0);
        switch (fault) {
            case "missing_quote" -> requirement.remove("quotes");
            case "wrong_quote" -> requirement.putArray("quotes").add("正文没有这句");
            case "wrong_source" -> ((ObjectNode) requirement.path("evidence").get(0)).put("sourceId", "actualAnswers.99");
            case "missing_answer" -> ((com.fasterxml.jackson.databind.node.ArrayNode) task.path("requirements")).remove(2);
            case "missing_gap" -> ((ObjectNode) task.path("additions").get(0)).remove("gap");
            case "invalid_dimensions" -> requirement.putArray("dimensions");
            case "mixed_coverage" -> ((ObjectNode) task.path("review")).putArray("requirementCoverage");
            case "newline_quote" -> requirement.putArray("quotes").add("写交接说明\n对象为接班同事");
            default -> throw new AssertionError(fault);
        }
        String reply = response(tree);
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(reply, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(reply, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input(), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        })).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void missingAnswerRepairUsesOnlyServerSourceIdentifiers() throws Exception {
        var incomplete = compact();
        ((com.fasterxml.jackson.databind.node.ArrayNode) incomplete.path("taskUnderstanding").path("requirements")).remove(2);
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(incomplete), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var envelope = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            String instruction = envelope.path("messages").get(0).path("content").asText();
            assertThat(instruction).contains("actualAnswers.1").doesNotContain("接班同事", "交接对象是谁", "需要通知谁");
        }).andRespond(withSuccess(response(compact()), MediaType.APPLICATION_JSON));
        var result = provider.enhance(input(), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.taskUnderstanding().requirements()).hasSize(3);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void planKeepsItsCompleteImpactProtocolAndDoesNotClaimBodyCoverage(boolean boundAnswers) throws Exception {
        var requirements = new java.util.ArrayList<NaturalTaskUnderstanding.Requirement>();
        requirements.add(new NaturalTaskUnderstanding.Requirement("写交接说明", List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", "写交接说明")),
                List.of(NaturalTaskUnderstanding.Dimension.GOAL)));
        if (boundAnswers) {
            for (int index = 0; index < 2; index++) requirements.add(new NaturalTaskUnderstanding.Requirement("接班同事",
                    List.of(new NaturalTaskUnderstanding.Evidence("actualAnswers." + index, "接班同事")),
                    List.of(NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION)));
        }
        var task = new NaturalTaskUnderstanding("写交接说明", "交接说明", List.copyOf(requirements), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        var body = json.valueToTree(Map.of("summary", "已有信息足以继续交付", "questions", List.of(), "taskUnderstanding", task));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var envelope = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            String user = envelope.path("messages").get(1).path("content").asText();
            assertThat(json.readTree(user.substring(user.indexOf('{'))).has("requiredSourceIds")).isFalse();
            assertThat(envelope.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptInstructionsV5.PLAN);
        }).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        var input = boundAnswers ? input() : new NaturalPromptInput("写交接说明", context(), List.of(), List.of(), false, List.of(), null).withStrategyVersion("5");
        var result = provider.plan(input, draft -> {
            draft.taskUnderstanding().review().validatePlan(Set.of());
            return draft;
        });
        assertThat(result.questions()).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MESSAGE", "JAVA_COMPLETE", "JSON_EXTRACT", "SOURCE_REPORT", "PPT_DESIGN", "HANDOFF_GUIDE"})
    void replaysExistingActualSourcesAndEveryCoverageAnchorUnchanged(String caseId) throws Exception {
        var directory = java.nio.file.Path.of("../../docs/testing/evidence/natural-coverage-workflow-2026-10-20");
        var protocol = json.readTree(java.nio.file.Files.readString(directory.resolve("protocol-candidate2.json")));
        String originalId = caseId + "_V4_GEN";
        String jobId = protocol.path("currentJobs").path(originalId).asText(originalId);
        com.fasterxml.jackson.databind.JsonNode trace = null;
        for (String line : java.nio.file.Files.readAllLines(directory.resolve("transports.jsonl"))) {
            var candidate = json.readTree(line);
            if (candidate.path("jobId").asText().equals(jobId)) { trace = candidate; break; }
        }
        assertThat(trace).isNotNull();
        String user = trace.path("request").path("messages").get(1).path("content").asText();
        var payload = (ObjectNode) json.readTree(user.substring(user.indexOf('{')));
        payload.remove("taskSources"); payload.putNull("modelId");
        var previousInput = json.treeToValue(payload, NaturalPromptInput.class).withStrategyVersion("4");
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(json.writeValueAsString(trace.path("response")), MediaType.APPLICATION_JSON));
        var previous = provider.enhance(previousInput, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        server.verify();
        server.reset();

        ObjectNode task = json.valueToTree(previous.taskUnderstanding());
        for (String category : List.of("requirements", "additions")) {
            var coverage = category.equals("requirements") ? previous.taskUnderstanding().review().requirementCoverage()
                    : previous.taskUnderstanding().review().additionCoverage();
            for (int index = 0; index < task.path(category).size(); index++) {
                var entry = (ObjectNode) task.path(category).get(index);
                entry.remove("text");
                entry.set("quotes", json.valueToTree(coverage.get(index).quotes()));
            }
        }
        ((ObjectNode) task.path("review")).remove(List.of("requirementCoverage", "additionCoverage"));
        var wire = json.valueToTree(Map.of("optimizedPrompt", previous.optimizedPrompt(), "framework", previous.framework(), "taskUnderstanding", task));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(wire), MediaType.APPLICATION_JSON));
        var current = provider.enhance(previousInput.withStrategyVersion("5"), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(current.optimizedPrompt()).isEqualTo(previous.optimizedPrompt());
        assertThat(current.taskUnderstanding().goal()).isEqualTo(previous.taskUnderstanding().goal());
        assertThat(current.taskUnderstanding().deliverable()).isEqualTo(previous.taskUnderstanding().deliverable());
        assertThat(current.taskUnderstanding().openDecisions()).isEqualTo(previous.taskUnderstanding().openDecisions());
        assertThat(current.taskUnderstanding().review()).isEqualTo(previous.taskUnderstanding().review());
        assertThat(current.taskUnderstanding().requirements()).extracting(NaturalTaskUnderstanding.Requirement::evidence)
                .containsExactlyElementsOf(previous.taskUnderstanding().requirements().stream().map(NaturalTaskUnderstanding.Requirement::evidence).toList());
        assertThat(current.taskUnderstanding().requirements()).extracting(NaturalTaskUnderstanding.Requirement::dimensions)
                .containsExactlyElementsOf(previous.taskUnderstanding().requirements().stream().map(NaturalTaskUnderstanding.Requirement::dimensions).toList());
        assertThat(current.taskUnderstanding().additions()).extracting(NaturalTaskUnderstanding.Addition::gap)
                .containsExactlyElementsOf(previous.taskUnderstanding().additions().stream().map(NaturalTaskUnderstanding.Addition::gap).toList());
        assertThat(current.taskUnderstanding().additions()).extracting(NaturalTaskUnderstanding.Addition::benefit)
                .containsExactlyElementsOf(previous.taskUnderstanding().additions().stream().map(NaturalTaskUnderstanding.Addition::benefit).toList());
        assertThat(json.writeValueAsString(wire).length()).isLessThan(json.writeValueAsString(Map.of("optimizedPrompt", previous.optimizedPrompt(),
                "framework", previous.framework(), "taskUnderstanding", previous.taskUnderstanding())).length());
        server.verify();
    }

    @Test
    void freezesVersionFourAndKeepsTheCurrentPlanningInstruction() {
        assertThat(NaturalPromptInstructions.planningInstruction("5")).isEqualTo(NaturalPromptInstructions.planningInstruction("4"));
        assertThat(NaturalPromptInstructions.enhancementInstruction("4")).contains("requirementCoverage", "additionCoverage");
        assertThat(NaturalPromptInstructions.enhancementInstruction("5")).doesNotContain("requirementCoverage", "additionCoverage");
        assertThat(NaturalPromptInstructions.requiresTaskReview("4")).isTrue();
    }

    @Test
    void directGenerationKeepsTheOriginalRequestWithoutTheBoundAnswerChecklist() throws Exception {
        var body = compact();
        var requirements = (com.fasterxml.jackson.databind.node.ArrayNode) body.path("taskUnderstanding").path("requirements");
        requirements.remove(2); requirements.remove(1);
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var envelope = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            String user = envelope.path("messages").get(1).path("content").asText();
            assertThat(json.readTree(user.substring(user.indexOf('{'))).has("requiredSourceIds")).isFalse();
            assertThat(envelope.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptInstructionsV5.ENHANCE);
        }).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        provider.enhance(new NaturalPromptInput("写交接说明", context(), List.of(), List.of(), false, List.of(), null).withStrategyVersion("5"), draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        server.verify();
    }

    @Test
    void actualFailedAnswerRecordsRemainRejectedRatherThanAutoFilled() throws Exception {
        var path = java.nio.file.Path.of("../../docs/testing/evidence/natural-utility-cost-2026-10-21/transports-candidate1.jsonl");
        var traces = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (String line : java.nio.file.Files.readAllLines(path)) {
            var trace = json.readTree(line);
            if (trace.path("jobId").asText().equals("PLAN_PURCHASE_V5_PLAN_GEN")) traces.add(trace);
        }
        assertThat(traces).hasSize(2);
        String user = traces.getFirst().path("request").path("messages").get(1).path("content").asText();
        var payload = (ObjectNode) json.readTree(user.substring(user.indexOf('{')));
        payload.remove("taskSources"); payload.putNull("modelId");
        var input = json.treeToValue(payload, NaturalPromptInput.class);
        for (var trace : traces) server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(json.writeValueAsString(trace.path("response")), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(input, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        })).isInstanceOf(NaturalTaskReview.MissingSources.class);
        server.verify();
    }

    private NaturalPromptInput input() {
        return new NaturalPromptInput("写交接说明", context(), List.of(new PlanAnswer("q1", "交接对象是谁？", "接班同事"),
                new PlanAnswer("q2", "需要通知谁？", "接班同事")), List.of(), false, List.of(), null).withStrategyVersion("5");
    }

    private ContextSnapshot context() { return new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY"); }

    private ObjectNode compact() throws Exception {
        return (ObjectNode) json.readTree("""
                {"optimizedPrompt":"写交接说明，对象为接班同事。期限尚未确认，不替用户确定日期。","framework":"APE",
                 "taskUnderstanding":{"goal":"写交接说明","deliverable":"交接说明",
                  "requirements":[
                   {"dimensions":["GOAL"],"evidence":[{"sourceId":"rawPrompt","quote":"写交接说明"}],"quotes":["写交接说明"]},
                   {"dimensions":["USER_CONFIRMATION"],"evidence":[{"sourceId":"actualAnswers.0","quote":"接班同事","origin":"PROJECT_CONTEXT"}],"quotes":["对象为接班同事"]},
                   {"dimensions":["USER_CONFIRMATION"],"evidence":[{"sourceId":"actualAnswers.1","quote":"接班同事"}],"quotes":["对象为接班同事"]}],
                  "additions":[{"gap":"还需说明未核实期限只影响对应安排","benefit":"不把缺失信息改成确定安排","quotes":["期限尚未确认，不替用户确定日期"]}],
                  "review":{"inputReadiness":"SUFFICIENT"}}}
                """);
    }

    private String response(com.fasterxml.jackson.databind.JsonNode body) throws Exception {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", json.writeValueAsString(body)))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }
}
