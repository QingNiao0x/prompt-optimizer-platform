package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 用已有真实首稿回放两阶段接线及防退化；不把固定响应当新鲜模型或作品质量证据。 */
class NaturalBodyFirstProviderTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final URI endpoint = URI.create("https://provider.invalid/chat/completions");

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"59,U02", "59,V02", "61,U02", "61,V02", "62,U02", "62,V02"})
    void writesBodyBeforeRecordsAndOnlyReturnsAfterAllOriginalChecks(String version, String id) throws Exception {
        var fixture = fixture(id); var input = input(fixture).withStrategyVersion(version); ObjectNode original = draft(fixture);
        var normalized = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), original, input).orElseThrow()
                .apply(responseContent(fixture.path("patchResponse")));
        ObjectNode record = normalized.deepCopy(); record.remove("optimizedPrompt");
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var requests = new ArrayList<JsonNode>();
        expect(server, Map.of("optimizedPrompt", original.path("optimizedPrompt").asText()), requests);
        expect(server, record, requests);
        var result = provider(builder).enhance(input, value -> checked(value, input));
        assertThat(result.optimizedPrompt()).isEqualTo(original.path("optimizedPrompt").asText());
        assertThat(result.completedStages()).containsExactly(
                com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.BODY_GENERATION,
                com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.RECORD_LINKING);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("messages").path(0).path("content").asText()).isEqualTo(NaturalPromptBodyFirstInstructions.bodyInstruction(version));
        assertThat(requests.get(1).path("messages").path(0).path("content").asText()).isEqualTo(NaturalPromptBodyFirstInstructions.RECORD);
        ObjectNode first = user(requests.get(0)), second = user(requests.get(1));
        assertThat(second.path("fidelityDraft")).isEqualTo(json.valueToTree(Map.of("optimizedPrompt", result.optimizedPrompt())));
        second.remove("fidelityDraft"); assertThat(second).isEqualTo(first);
        first.remove("inputBoundary"); assertThat(first).isEqualTo(json.valueToTree(input));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"59", "61", "62"})
    void canRepairRecordMetadataWithoutReopeningTheFirstBody(String version) throws Exception {
        var fixture = fixture("U02"); var input = input(fixture).withStrategyVersion(version); var original = draft(fixture);
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var requests = new ArrayList<JsonNode>();
        expect(server, Map.of("optimizedPrompt", original.path("optimizedPrompt").asText()), requests);
        ObjectNode record = original.deepCopy(); record.remove("optimizedPrompt");
        expect(server, record, requests); expect(server, responseContent(fixture.path("patchResponse")), requests);
        var result = provider(builder).enhance(input, value -> checked(value, input));
        assertThat(result.optimizedPrompt()).isEqualTo(original.path("optimizedPrompt").asText());
        assertThat(requests).hasSize(3);
        assertThat(requests.get(2).path("messages").path(0).path("content").asText()).isEqualTo(NaturalPromptMetadataRepair.INSTRUCTION);
        ObjectNode second = user(requests.get(1)), third = user(requests.get(2));
        third.remove("protocolRepair"); assertThat(third).isEqualTo(second); server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"59", "61", "62"})
    void recordCannotEchoOrRewriteTheBodyAndFailureDoesNotRegenerateIt(String version) throws Exception {
        var fixture = fixture("V02"); var input = input(fixture).withStrategyVersion(version); var original = draft(fixture);
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var requests = new ArrayList<JsonNode>();
        expect(server, Map.of("optimizedPrompt", original.path("optimizedPrompt").asText()), requests);
        expect(server, original, requests); expect(server, original, requests);
        assertThatThrownBy(() -> provider(builder).enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(3);
        assertThat(requests.subList(1, 3)).allSatisfy(request -> assertThat(user(request).has("fidelityDraft")).isTrue());
        server.verify();
    }

    @Test
    void sensitiveFirstBodyIsNotForwardedToRecordStage() throws Exception {
        var fixture = fixture("V02"); var input = input(fixture);
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        expect(server, Map.of("optimizedPrompt", "api_key=sk-synthetic-sensitive-123456"), requests);
        expect(server, Map.of("optimizedPrompt", "api_key=sk-synthetic-sensitive-123456"), requests);
        assertThatThrownBy(() -> provider(builder).enhance(input, value -> value)).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(2); assertThat(requests).allSatisfy(request -> assertThat(user(request).has("fidelityDraft")).isFalse());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"59", "61"})
    void missingActualAnswerStillBlocksAndDoesNotReopenBodyGeneration(String version) throws Exception {
        var fixture = fixture("U02"); var source = input(fixture);
        var input = new NaturalPromptInput(source.rawPrompt(), source.context(),
                List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer("ownership", "这项由谁确认？", "本轮由我确认")),
                source.conversation(), source.mandatoryRules(), source.modelId(), source.planningContext()).withStrategyVersion(version);
        var original = draft(fixture);
        var corrected = NaturalPromptMetadataRepair.inspect(json.valueToTree(source), original, source).orElseThrow()
                .apply(responseContent(fixture.path("patchResponse")));
        ObjectNode record = corrected.deepCopy(); record.remove("optimizedPrompt");
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        expect(server, Map.of("optimizedPrompt", original.path("optimizedPrompt").asText()), requests);
        expect(server, record, requests); expect(server, record, requests);
        assertThatThrownBy(() -> provider(builder).enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(3);
        assertThat(requests.subList(1, 3)).allSatisfy(request -> {
            assertThat(user(request).has("fidelityDraft")).isTrue();
            assertThat(user(request).path("actualAnswers").size()).isEqualTo(1);
        }); server.verify();
    }

    @Test
    void completionProposalsRemainAdvisoryAndDoNotEnterTheRecordInput() throws Exception {
        var fixture = fixture("U02"); var input = input(fixture).withStrategyVersion("68");
        var original = draft(fixture);
        var fixed = (ObjectNode) NaturalPromptMetadataRepair.inspect(json.valueToTree(input), original, input.withStrategyVersion("59")).orElseThrow()
                .apply(responseContent(fixture.path("patchResponse")));
        var task = fixed.path("task"); var evidence = task.path("requirements").get(0).path("evidence");
        var prepared = json.createObjectNode();
        prepared.putArray("actions").addObject().put("text", task.path("goal").asText()).put("timing", "CURRENT").put("condition", "").set("evidence", evidence);
        prepared.putObject("response").put("basis", "EXPLICIT").put("description", task.path("deliverable").asText()).set("evidence", evidence);
        if (NaturalPromptCompletionPreparationInstructions.supports(input.strategyVersion())) prepared.putArray("additions");
        var requirements = prepared.putArray("requirements");
        for (var item : task.path("requirements")) { ObjectNode requirement = item.deepCopy(); requirement.remove("bodyQuotes"); requirements.add(requirement); }
        prepared.putArray("additions").addObject().put("text", "说明可实施的操作过程").put("gap", "简略需求尚未表达执行过程").put("benefit", "使原方案可直接使用");
        var expected = json.treeToValue(prepared, NaturalPromptCompletionPreparation.class).resolve(input);
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        expect(server, prepared, requests); expect(server, Map.of("optimizedPrompt", fixed.path("optimizedPrompt").asText()), requests);
        ObjectNode record = fixed.deepCopy(); record.remove("optimizedPrompt"); expect(server, record, requests);
        var result = provider(builder).enhance(input, value -> checked(value, input));
        assertThat(result.optimizedPrompt()).isEqualTo(fixed.path("optimizedPrompt").asText());
        assertThat(requests).hasSize(3);
        assertThat(user(requests.get(1)).path("completionSuggestions")).isEqualTo(json.valueToTree(expected.suggestions()));
        assertThat(user(requests.get(2)).has("completionSuggestions")).isFalse();
        assertThat(user(requests.get(1)).path("taskSources")).isEqualTo(json.valueToTree(input).path("taskSources"));
        assertThat(NaturalPromptCompletionPreparationInstructions.CORRECTED_PREPARE).doesNotContain("不寻找补全项", "\"dimensions\":[\"适用维度\"]");
        for (var dimension : NaturalTaskUnderstanding.Dimension.values()) {
            assertThat(NaturalPromptCompletionPreparationInstructions.CORRECTED_PREPARE).contains(dimension.name());
        }
        ObjectNode invalid = prepared.deepCopy();
        ((ObjectNode) invalid.withArray("additions").get(0)).put("gap", "");
        assertThatThrownBy(() -> json.treeToValue(invalid, NaturalPromptCompletionPreparation.class).resolve(input)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"69", "70"})
    void actualContextGoalDiagnosticDoesNotBlockTheUnchangedBodyWhenUserGoalExists(String version) throws Exception {
        JsonNode fixture; try (var stream = getClass().getResourceAsStream("/natural-diagnostic-roles/first68-record.json")) { fixture = json.readTree(stream); }
        var input = json.treeToValue(fixture.path("input"), NaturalPromptInput.class).withStrategyVersion(version);
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        expect(server, fixture.path("preparation"), requests); expect(server, fixture.path("body"), requests); expect(server, fixture.path("record"), requests);
        var result = provider(builder).enhance(input, value -> checked(value, input));
        var bound = (ObjectNode) fixture.path("record").deepCopy(); bound.put("optimizedPrompt", fixture.path("body").path("optimizedPrompt").asText());
        var original = json.treeToValue(bound, NaturalPromptPhasePayloads.Enhancement.class).toDraft(input,
                new com.promptoptimizer.enhancement.domain.ProviderMetadata("deepseek", "deepseek:deepseek-flash", false));
        assertThat(result.optimizedPrompt()).isEqualTo(original.optimizedPrompt());
        assertThat(result.taskUnderstanding().review()).isEqualTo(original.taskUnderstanding().review());
        assertThat(result.taskUnderstanding().requirements()).hasSameSizeAs(original.taskUnderstanding().requirements());
        for (int i = 0; i < result.taskUnderstanding().requirements().size(); i++) {
            assertThat(result.taskUnderstanding().requirements().get(i).text()).isEqualTo(original.taskUnderstanding().requirements().get(i).text());
            assertThat(result.taskUnderstanding().requirements().get(i).evidence()).isEqualTo(original.taskUnderstanding().requirements().get(i).evidence());
        }
        assertThat(result.taskUnderstanding().requirements().get(0).dimensions()).contains(NaturalTaskUnderstanding.Dimension.GOAL);
        assertThat(result.taskUnderstanding().requirements().get(1).dimensions()).containsExactly(NaturalTaskUnderstanding.Dimension.OBJECT, NaturalTaskUnderstanding.Dimension.FORMAT);
        assertThat(requests).hasSize(3);
        ObjectNode writer = user(requests.get(1)), recordInput = user(requests.get(2));
        if (version.equals("70")) {
            assertThat(writer.has("taskSources")).isFalse();
            assertThat(writer.path("completionSuggestions").size()).isEqualTo(1);
            assertThat(writer.path("preparedRequirements").get(0).size()).isEqualTo(1);
            assertThat(recordInput.path("taskSources")).isEqualTo(json.valueToTree(input).path("taskSources"));
            assertThat(recordInput.path("preparedRequirements").get(0).has("evidence")).isTrue();
            for (String field : new String[]{"rawPrompt", "context", "actualAnswers", "conversation", "mandatoryRules", "planningContext"}) {
                assertThat(writer.path(field)).isEqualTo(recordInput.path(field));
            }
        }
        server.verify();
        assertThat(NaturalPromptDiagnosticRoles.normalize(original, input.withStrategyVersion("68"))).isSameAs(original);
        assertThatThrownBy(() -> NaturalPromptRequirementsOnlyPreparation.validateUserGoals(original.taskUnderstanding())).isInstanceOf(ProviderException.class);
        assertThat(NaturalPromptBodyFirstInstructions.bodyInstruction("69", true)).isEqualTo(NaturalPromptBodyFirstInstructions.bodyInstruction("68", true));
        assertThat(NaturalPromptCompletionPreparationInstructions.preparationInstruction("69")).isEqualTo(NaturalPromptCompletionPreparationInstructions.preparationInstruction("68"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"noUserGoal", "onlyGoal", "forgedQuote", "missingBodyQuote"})
    void diagnosticNormalizationCannotInventAUserGoalOrRepairSourcesAndCoverage(String defect) throws Exception {
        JsonNode fixture; try (var stream = getClass().getResourceAsStream("/natural-diagnostic-roles/first68-record.json")) { fixture = json.readTree(stream); }
        var input = json.treeToValue(fixture.path("input"), NaturalPromptInput.class).withStrategyVersion("69");
        var bound = (ObjectNode) fixture.path("record").deepCopy(); bound.put("optimizedPrompt", fixture.path("body").path("optimizedPrompt").asText());
        var requirements = bound.path("task").path("requirements");
        if (defect.equals("noUserGoal")) ((ObjectNode) requirements.get(0)).putArray("dimensions").add("OBJECT");
        if (defect.equals("onlyGoal")) ((ObjectNode) requirements.get(1)).putArray("dimensions").add("GOAL");
        if (defect.equals("forgedQuote")) ((ObjectNode) requirements.get(0).path("evidence").get(0)).put("quote", "未在任何来源中出现的确认");
        if (defect.equals("missingBodyQuote")) ((ObjectNode) requirements.get(0)).putArray("bodyQuotes").add("正文不存在的覆盖引文");
        assertThatThrownBy(() -> {
            var original = json.treeToValue(bound, NaturalPromptPhasePayloads.Enhancement.class).toDraft(input,
                    new com.promptoptimizer.enhancement.domain.ProviderMetadata("deepseek", "deepseek:deepseek-flash", false));
            var normalized = NaturalPromptDiagnosticRoles.normalize(original, input);
            NaturalPromptRequirementsOnlyPreparation.validateUserGoals(normalized.taskUnderstanding());
        }).isInstanceOf(ProviderException.class);
    }

    private NaturalPromptDraft checked(NaturalPromptDraft draft, NaturalPromptInput input) {
        var task = draft.taskUnderstanding().resolve(input);
        try {
            var type = com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService.class;
            var method = type.getDeclaredMethod("validateBody", NaturalPromptDraft.class, List.class, String.class); method.setAccessible(true);
            String assembled = (String) method.invoke(new com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService(null, null, null, null, null, null),
                    draft, input.mandatoryRules(), input.strategyVersion());
            task.review().validateEnhancement(task, draft.optimizedPrompt()); task.review().validateEnhancement(task, assembled);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime; throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return draft;
    }

    @ParameterizedTest
    @ValueSource(strings = {"P00_V62_GEN", "P02_CONFIRMED_V62_GEN", "P02_UNKNOWN_V62_GEN"})
    void validActualPlanGenerationsKeepTheir62RequestsAndOutputsExceptVersion(String id) throws Exception {
        JsonNode fixture;
        try (var stream = getClass().getResourceAsStream("/natural-body-first/frozen62-plan-generations.json")) { fixture = json.readTree(stream); }
        JsonNode sample = null;
        for (var item : fixture.path("cases")) if (item.path("id").asText().equals(id)) sample = item;
        assertThat(sample).isNotNull(); var traces = sample.path("traces");
        ObjectNode originalInput = user(traces.get(0).path("trace").path("request"));
        originalInput.remove(List.of("taskSources", "inputBoundary", "taskScope", "preparedRequirements"));
        var input = json.treeToValue(originalInput, NaturalPromptInput.class).withStrategyVersion("63");
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        for (var item : traces) expect(server, responseContent(item.path("trace").path("response")), requests);
        var result = provider(builder).enhance(input, value -> checked(value, input));
        String expected = traces.size() == 2 ? responseContent(traces.get(0).path("trace").path("response")).path("optimizedPrompt").asText()
                : responseContent(traces.get(1).path("trace").path("response")).path("optimizedPrompt").asText();
        assertThat(result.optimizedPrompt()).isEqualTo(expected); assertThat(requests).hasSize(traces.size());
        for (int i = 0; i < requests.size(); i++) {
            JsonNode actual = requests.get(i), prior = traces.get(i).path("trace").path("request");
            ObjectNode actualUser = user(actual), priorUser = user(prior); actualUser.put("strategyVersion", "62");
            assertThat(actualUser).isEqualTo(priorUser);
            assertThat(actual.path("messages").get(0)).isEqualTo(prior.path("messages").get(0));
            assertThat(actual.path("thinking")).isEqualTo(prior.path("thinking"));
            assertThat(actual.path("reasoning_effort")).isEqualTo(prior.path("reasoning_effort"));
            assertThat(actual.path("max_tokens")).isEqualTo(prior.path("max_tokens"));
        }
        server.verify();
    }

    @Test
    void actualEngineeringSourceRepairSelectsOriginalLineAndNeverRewritesBody() throws Exception {
        JsonNode fixture;
        try (var stream = getClass().getResourceAsStream("/natural-metadata-repair/engineering62-source-reference.json")) { fixture = json.readTree(stream); }
        var raw = (ObjectNode) fixture.path("originalInput").deepCopy(); raw.remove(List.of("taskSources", "inputBoundary", "fidelityDraft"));
        var input = json.treeToValue(raw, NaturalPromptInput.class).withStrategyVersion("63");
        ObjectNode original = fixture.path("candidate").deepCopy(); String body = original.path("optimizedPrompt").asText();
        var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), original, input).orElseThrow();
        String repairMessage = repair.userMessage(json); JsonNode protocol = json.readTree(repairMessage.substring(repairMessage.indexOf('{'))).path("protocolRepair");
        String selected = protocol.path("sourceReferences").get(0).path("id").asText();
        var patch = json.createObjectNode(); patch.putArray("changes").addObject().put("path", "/task/requirements/0/evidence").putArray("value").add(selected);
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        expect(server, Map.of("optimizedPrompt", body), requests); var record = original.deepCopy(); record.remove("optimizedPrompt");
        expect(server, record, requests); expect(server, patch, requests);
        var result = provider(builder).enhance(input, value -> checked(value, input));
        assertThat(result.optimizedPrompt()).isEqualTo(body); assertThat(requests).hasSize(3);
        assertThat(requests.get(2).path("messages").get(0).path("content").asText()).isEqualTo(repair.instruction());
        assertThat(user(requests.get(2)).path("protocolRepair").path("mode").asText()).isEqualTo("METADATA_REFERENCES");
        assertThat(result.taskUnderstanding().requirements().getFirst().evidence().getFirst().quote())
                .isEqualTo(input.rawPrompt().lines().findFirst().orElseThrow());
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"62,用表格", "62,尚未决定", "62,B组采用列表，A组不变",
            "63,用表格", "63,尚未决定", "63,B组采用列表，A组不变",
            "64,用表格", "64,尚未决定", "64,B组采用列表，A组不变",
            "65,用表格", "65,尚未决定", "65,B组采用列表，A组不变",
            "66,用表格", "66,尚未决定", "66,B组采用列表，A组不变", "67,用表格", "67,尚未决定", "67,B组采用列表，A组不变", "68,用表格", "68,尚未决定", "68,B组采用列表，A组不变", "69,用表格", "69,尚未决定", "69,B组采用列表，A组不变", "70,用表格", "70,尚未决定", "70,B组采用列表，A组不变"})
    void actualAnswersArePreparedOnceAndCarriedToBothBodyAndRecord(String version, String answer) throws Exception {
        // 合成响应只证明当前解释与原来源的传递，不声称模型语义已经正确。
        var input = new NaturalPromptInput("请整理A、B组方案，呈现方式由我确认；预算不超过500元，不修改文件。", null,
                List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer("format", "B组呈现方式？", answer)),
                List.of(), List.of(), "deepseek:deepseek-flash").withStrategyVersion(version);
        var prepared = effectivePreparation(input);
        String body = "请整理A、B组方案，B组呈现方式为“" + answer + "”；预算不超过500元，不修改文件。";
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        expect(server, prepared, requests); expect(server, Map.of("optimizedPrompt", body), requests);
        expect(server, effectiveRecord(input, body), requests);
        var result = provider(builder).enhance(input, value -> checked(value, input));
        assertThat(result.completedStages()).containsExactly(
                com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.REQUIREMENT_PREPARATION,
                com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.BODY_GENERATION,
                com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.RECORD_LINKING);
        assertThat(requests).hasSize(3);
        assertThat(requests.getFirst().path("messages").get(0).path("content").asText())
                .isEqualTo(NaturalPromptCompletionPreparationInstructions.supports(version) ? NaturalPromptCompletionPreparationInstructions.preparationInstruction(version) : Set.of("65", "66").contains(version) ? NaturalPromptBodyFirstInstructions.CURRENT_REQUIREMENTS_PREPARE
                        : NaturalPromptEffectiveRegistryInstructions.PREPARE_WITH_ANSWERS);
        ObjectNode writer = user(requests.get(1)), record = user(requests.get(2));
        assertThat(writer.path("preparedRequirements").size()).isEqualTo(1);
        if (version.equals("70")) {
            assertThat(writer.path("preparedRequirements").get(0).path("text")).isEqualTo(prepared.path("requirements").get(0).path("text"));
            assertThat(writer.path("preparedRequirements").get(0).has("evidence")).isFalse();
            assertThat(record.path("preparedRequirements").get(0).path("evidence").size()).isEqualTo(2);
        } else {
            assertThat(writer.path("preparedRequirements").get(0).path("reqId").asText()).isEqualTo("r0");
            assertThat(writer.path("preparedRequirements").get(0).path("evidence").size()).isEqualTo(2);
        }
        assertThat(writer.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
        assertThat(writer.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
        assertThat(record.path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(body);
        if (version.equals("70")) {
            assertThat(record.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
            assertThat(record.path("taskSources")).isEqualTo(json.valueToTree(input).path("taskSources"));
            for (String field : new String[]{"rawPrompt", "context", "actualAnswers", "conversation", "mandatoryRules", "planningContext"}) {
                assertThat(writer.path(field)).isEqualTo(record.path(field));
            }
        } else {
            record.remove("fidelityDraft"); if (NaturalPromptCompletionPreparationInstructions.supports(version)) writer.remove("completionSuggestions"); assertThat(record).isEqualTo(writer);
        }
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"64,U02", "64,V02", "65,U02", "65,V02", "66,U02", "66,V02", "67,U02", "67,V02", "68,U02", "68,V02", "69,U02", "69,V02"})
    void ordinaryPreparedRequestKeepsAllOriginalProtection(String version, String id) throws Exception {
        var fixture = fixture(id); var input = input(fixture).withStrategyVersion(version);
        var original = draft(fixture);
        // 夹具中的历史补丁使用59原协议；只先恢复同一合法正文/记录，不把旧补丁重标为64响应。
        var fixed = (ObjectNode) NaturalPromptMetadataRepair.inspect(json.valueToTree(input), original, input.withStrategyVersion("59")).orElseThrow()
                .apply(responseContent(fixture.path("patchResponse")));
        var prepared = json.createObjectNode(); var task = fixed.path("task");
        var evidence = task.path("requirements").get(0).path("evidence");
        var action = prepared.putArray("actions").addObject().put("text", task.path("goal").asText())
                .put("timing", "CURRENT").put("condition", ""); action.set("evidence", evidence);
        String deliverable = task.path("deliverable").asText();
        var response = prepared.putObject("response").put("basis", deliverable.isEmpty() ? "UNSPECIFIED" : "EXPLICIT")
                .put("description", deliverable);
        response.set("evidence", deliverable.isEmpty() ? json.createArrayNode() : evidence);
        if (NaturalPromptCompletionPreparationInstructions.supports(input.strategyVersion())) prepared.putArray("additions");
        var requirements = prepared.putArray("requirements");
        for (var item : task.path("requirements")) { ObjectNode requirement = item.deepCopy(); requirement.remove("bodyQuotes"); requirements.add(requirement); }
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        expect(server, prepared, requests); expect(server, Map.of("optimizedPrompt", fixed.path("optimizedPrompt").asText()), requests);
        ObjectNode record = fixed.deepCopy(); record.remove("optimizedPrompt"); expect(server, record, requests);
        var result = provider(builder).enhance(input, value -> checked(value, input));
        assertThat(result.optimizedPrompt()).isEqualTo(fixed.path("optimizedPrompt").asText());
        assertThat(result.completedStages()).containsExactly(
                com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.REQUIREMENT_PREPARATION,
                com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.BODY_GENERATION,
                com.promptoptimizer.enhancement.domain.NaturalProcessing.Stage.RECORD_LINKING);
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptCompletionPreparationInstructions.supports(version)
                ? NaturalPromptCompletionPreparationInstructions.preparationInstruction(version) : Set.of("65", "66").contains(version)
                ? NaturalPromptBodyFirstInstructions.CURRENT_REQUIREMENTS_PREPARE : NaturalPromptEffectiveRegistryInstructions.PREPARE);
        assertThat(requests.get(1).path("messages").get(0).path("content").asText())
                .isEqualTo(NaturalPromptBodyFirstInstructions.bodyInstruction(version, true));
        assertThat(user(requests.get(0)).has("preparedRequirements")).isFalse();
        ObjectNode writer = user(requests.get(1)), linked = user(requests.get(2));
        assertThat(writer.path("preparedRequirements").size()).isEqualTo(requirements.size());
        assertThat(writer.path("actualAnswers")).isEmpty();
        assertThat(writer.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
        assertThat(writer.path("context")).isEqualTo(json.valueToTree(input.context()));
        linked.remove("fidelityDraft"); if (NaturalPromptCompletionPreparationInstructions.supports(version)) writer.remove("completionSuggestions"); assertThat(linked).isEqualTo(writer);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingAnswer", "forgedSource"})
    void invalidEffectivePreparationStopsBeforeAnyBodyIsWritten(String defect) throws Exception {
        var input = new NaturalPromptInput("请给方案，不修改文件。", null,
                List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer("format", "呈现方式？", "未知")),
                List.of(), List.of(), "deepseek:deepseek-flash").withStrategyVersion("62");
        var prepared = effectivePreparation(input);
        var evidence = (com.fasterxml.jackson.databind.node.ArrayNode) prepared.path("requirements").get(0).path("evidence");
        if (defect.equals("missingAnswer")) evidence.remove(1);
        else ((ObjectNode) evidence.get(1)).put("quote", "用户未确认的来源原句");
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        expect(server, prepared, requests); expect(server, prepared, requests);
        assertThatThrownBy(() -> provider(builder).enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request -> assertThat(user(request).has("preparedRequirements")).isFalse());
        server.verify();
    }

    @Test
    void effectivePreparationDoesNotSubstituteForActualAnswerCoverageInTheFixedBody() throws Exception {
        var input = new NaturalPromptInput("请给方案，不修改文件。", null,
                List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer("format", "呈现方式？", "未知")),
                List.of(), List.of(), "deepseek:deepseek-flash").withStrategyVersion("62");
        String body = "请给方案，不修改文件；呈现方式未知。";
        var record = effectiveRecord(input, body);
        ((com.fasterxml.jackson.databind.node.ArrayNode) record.path("task").path("requirements").get(0).path("evidence")).remove(1);
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build(); var requests = new ArrayList<JsonNode>();
        expect(server, effectivePreparation(input), requests); expect(server, Map.of("optimizedPrompt", body), requests);
        expect(server, record, requests); expect(server, record, requests);
        assertThatThrownBy(() -> provider(builder).enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(4);
        assertThat(requests.subList(2, 4)).allSatisfy(request -> assertThat(user(request).path("fidelityDraft").path("optimizedPrompt").asText()).isEqualTo(body));
        server.verify();
    }

    /** 当前要求同时引用原始限制和实际回答；编号只能由服务端在resolve之后生成。 */
    private ObjectNode effectivePreparation(NaturalPromptInput input) {
        var result = json.createObjectNode();
        var action = result.putArray("actions").addObject().put("text", "整理方案").put("timing", "CURRENT").put("condition", "");
        action.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", input.rawPrompt());
        var response = result.putObject("response").put("basis", "EXPLICIT").put("description", "方案");
        response.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", input.rawPrompt());
        var requirement = result.putArray("requirements").addObject().put("text", "整理方案；呈现方式按实际回答解释，预算和不修改文件的独立要求仍保留。");
        requirement.putArray("dimensions").add("GOAL").add("CONDITION").add("USER_CONFIRMATION");
        var evidence = requirement.putArray("evidence");
        input.taskSources().forEach(source -> evidence.addObject().put("sourceId", source.id()).put("quote", source.text()));
        if (NaturalPromptCompletionPreparationInstructions.supports(input.strategyVersion())) result.putArray("additions");
        return result;
    }

    /** 固定正文登记仍须给出真实来源与正文引文，不能因为准备通过而省去实际题答。 */
    private ObjectNode effectiveRecord(NaturalPromptInput input, String body) {
        var record = json.createObjectNode().put("framework", "APE");
        var task = record.putObject("task").put("goal", "整理方案").put("deliverable", "方案").put("inputReadiness", "NEEDS_COMPLETION");
        var requirement = effectivePreparation(input).path("requirements").get(0).deepCopy();
        ((ObjectNode) requirement).putArray("bodyQuotes").add(body); task.putArray("requirements").add(requirement);
        task.putArray("additions"); task.putArray("openDecisions");
        return record;
    }

    private JsonNode fixture(String id) throws Exception {
        try (var stream = getClass().getResourceAsStream("/natural-metadata-repair/real-first-drafts-and-patches.json")) {
            for (var item : json.readTree(stream)) if (item.path("id").asText().equals(id)) return item;
        }
        throw new IllegalStateException("Missing fixture");
    }

    private ObjectNode draft(JsonNode fixture) throws Exception { return (ObjectNode) responseContent(fixture.path("firstResponse")); }
    private JsonNode responseContent(JsonNode response) throws Exception { return json.readTree(response.path("choices").path(0).path("message").path("content").asText()); }
    private NaturalPromptInput input(JsonNode fixture) throws Exception {
        ObjectNode data = user(fixture.path("request")); data.remove(List.of("taskSources", "inputBoundary"));
        return json.treeToValue(data, NaturalPromptInput.class).withStrategyVersion("59");
    }
    private ObjectNode user(JsonNode request) {
        try { String text = request.path("messages").path(1).path("content").asText(); return (ObjectNode) json.readTree(text.substring(text.indexOf('{'))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private OpenAiCompatibleNaturalPromptProvider provider(RestClient.Builder builder) {
        var route = new OpenAiCompatibleRouteProperties(); route.setProviderName("deepseek"); route.setEndpoint(endpoint); route.setApiKey("fixture"); route.setModel("deepseek-flash");
        var properties = new OpenAiCompatibleProperties(); properties.setMultiProviderEnabled(true); properties.setDefaultProvider("deepseek"); properties.setProviders(Map.of("deepseek", route)); properties.setMaxTokens(8192);
        return new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }
    private void expect(MockRestServiceServer server, Object body, List<JsonNode> requests) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", json.writeValueAsString(body))))));
        server.expect(requestTo(endpoint)).andExpect(request -> requests.add(json.readTree(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8))))
                .andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
