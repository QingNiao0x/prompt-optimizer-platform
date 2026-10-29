package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 证明补丁的写入范围与原输入保留，不把修复成功视为正文语义正确。 */
class NaturalPromptMetadataRepairTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void repairsOnlyTheErroneousDimensionAndPreservesBodyAndRequirementText() throws Exception {
        var input = input(); var candidate = candidate(input); var original = candidate.deepCopy();
        var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), candidate, input).orElseThrow();
        assertThat(repair.issues()).extracting(NaturalPromptMetadataRepair.Issue::code).containsExactly("CONTEXT_GOAL_DIMENSION");
        var patched = repair.apply(patch("/task/requirements/1/dimensions", json.valueToTree(List.of("CONDITION"))));
        assertThat(patched.path("optimizedPrompt")).isEqualTo(original.path("optimizedPrompt"));
        assertThat(patched.at("/task/requirements/1/text")).isEqualTo(original.at("/task/requirements/1/text"));
        assertThat(candidate).isEqualTo(original); // Inspect/apply cannot alter the frozen original.
        var payload = json.treeToValue(patched, NaturalPromptPhasePayloads.Enhancement.class);
        var draft = payload.toDraft(input, new com.promptoptimizer.enhancement.domain.ProviderMetadata("fixture", "fixture", false));
        NaturalPromptRequirementsOnlyPreparation.validateUserGoals(draft.taskUnderstanding());
    }

    @Test
    void correctionKeepsFullInputAndActualAnswersOutsideTheRepairData() throws Exception {
        var input = input(); ObjectNode original = json.valueToTree(input);
        var repair = NaturalPromptMetadataRepair.inspect(original, candidate(input), input).orElseThrow();
        String user = repair.userMessage(json); var data = (ObjectNode) json.readTree(user.substring(user.indexOf('{')));
        assertThat(data.path("protocolRepair").path("mode").asText()).isEqualTo("METADATA_ONLY");
        data.remove("protocolRepair"); assertThat(data).isEqualTo(original);
    }

    @Test
    void reportsSourceMismatchWithoutManufacturingAReplacementQuote() {
        var input = input(); var candidate = candidate(input);
        ((ObjectNode) candidate.at("/task/requirements/0/evidence/0")).put("quote", "原文不存在的引文");
        var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), candidate, input).orElseThrow();
        assertThat(repair.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("SOURCE_QUOTE_MISMATCH");
            assertThat(issue.path()).isEqualTo("/task/requirements/0/evidence");
            assertThat(issue.sourceId()).isEqualTo("rawPrompt");
        });
        assertThat(candidate.at("/task/requirements/0/evidence/0/quote").asText()).isEqualTo("原文不存在的引文");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/optimizedPrompt", "/task/goal", "/task/requirements", "/task/requirements/0/text", "/task/requirements/0/dimensions", "/task/additions"})
    void rejectsChangesOutsideTheSpecificFailedMetadata(String path) {
        var input = input(); var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), candidate(input), input).orElseThrow();
        assertThatThrownBy(() -> repair.apply(patch(path, json.valueToTree("越界")))).isInstanceOf(ProviderException.class);
    }

    @Test
    void refusesWholeResponseEmptyChangesAndRepeatedPaths() {
        var input = input(); var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), candidate(input), input).orElseThrow();
        assertThatThrownBy(() -> repair.apply(candidate(input))).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> repair.apply(json.createObjectNode().set("changes", json.createArrayNode()))).isInstanceOf(ProviderException.class);
        var duplicate = patch("/task/requirements/1/dimensions", json.valueToTree(List.of("CONDITION")));
        duplicate.withArray("changes").add(duplicate.path("changes").get(0).deepCopy());
        assertThatThrownBy(() -> repair.apply(duplicate)).isInstanceOf(ProviderException.class);
    }

    @Test
    void doesNotInventAUserGoalOrExposeSensitiveCandidate() {
        var input = input(); var noUserGoal = candidate(input);
        ((ObjectNode) noUserGoal.at("/task/requirements/0")).putArray("dimensions").add("CONDITION");
        assertThat(NaturalPromptMetadataRepair.inspect(json.valueToTree(input), noUserGoal, input)).isEmpty();
        var sensitive = candidate(input); sensitive.put("optimizedPrompt", "sk-" + "A".repeat(40));
        assertThat(NaturalPromptMetadataRepair.inspect(json.valueToTree(input), sensitive, input)).isEmpty();
    }

    private NaturalPromptInput input() {
        var context = new ContextSnapshot("业务目的是评估是否可靠。", List.of(), List.of(), List.of(), List.of(), "COMPLETE", List.of(), List.of(), List.of(), "v3");
        return new NaturalPromptInput("请根据背景制定方案。", context, List.of(new PlanAnswer("budget", "预算？", "预算仍未知")), List.of(), List.of(), null).withStrategyVersion("56");
    }

    @Test
    void actualEngineeringCopyFailureCanBeRepairedByExplicitOriginalLineSelection() throws Exception {
        var fixture = sourceReferenceFixture();
        var raw = (ObjectNode) fixture.path("originalInput").deepCopy();
        raw.remove(List.of("taskSources", "inputBoundary", "fidelityDraft"));
        var input = json.treeToValue(raw, NaturalPromptInput.class).withStrategyVersion("63");
        var body = fixture.path("candidate");
        var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), body, input).orElseThrow();
        var data = repairData(repair);
        assertThat(data.path("mode").asText()).isEqualTo("METADATA_REFERENCES");
        assertThat(data.path("referencePaths").has("/task/requirements/0/evidence")).isTrue();
        JsonNode selection = null;
        for (var option : data.path("sourceReferences")) {
            var source = input.taskSources().stream().filter(value -> value.id().equals(option.path("sourceId").asText())).findFirst().orElseThrow();
            assertThat(source.text()).contains(option.path("quote").asText());
            if (option.path("sourceId").asText().equals("rawPrompt") && option.path("quote").asText().endsWith("保持现有页面和错误码。")) selection = option;
        }
        assertThat(selection).isNotNull();
        var patched = repair.apply(patch("/task/requirements/0/evidence", json.valueToTree(List.of(selection.path("id").asText()))));
        assertThat(patched.path("optimizedPrompt")).isEqualTo(body.path("optimizedPrompt"));
        assertThat(patched.at("/task/requirements/0/text")).isEqualTo(body.at("/task/requirements/0/text"));
        var draft = json.treeToValue(patched, NaturalPromptPhasePayloads.Enhancement.class)
                .toDraft(input, new com.promptoptimizer.enhancement.domain.ProviderMetadata("fixture", "fixture", false));
        var task = draft.taskUnderstanding().resolve(input);
        NaturalPromptRequirementsOnlyPreparation.validateUserGoals(task);
        task.review().validateEnhancement(task, draft.optimizedPrompt());
        assertThat(repair.instruction()).contains("sourceReferences编号", "不等于语义正确");
        // 选择源内原句只解决逐字引用问题；本测试不把它升级为语义改善或真实模型通过。
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownReference", "duplicate", "literalEvidence", "empty"})
    void referencePathsRejectFreeCopyUnknownDuplicateAndEmptySelections(String defect) throws Exception {
        var fixture = sourceReferenceFixture(); var raw = (ObjectNode) fixture.path("originalInput").deepCopy();
        raw.remove(List.of("taskSources", "inputBoundary", "fidelityDraft"));
        var input = json.treeToValue(raw, NaturalPromptInput.class).withStrategyVersion("63");
        var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), fixture.path("candidate"), input).orElseThrow();
        String id = repairData(repair).path("referencePaths").path("/task/requirements/0/evidence").get(0).asText();
        JsonNode value = switch (defect) {
            case "unknownReference" -> json.valueToTree(List.of("s999999"));
            case "duplicate" -> json.valueToTree(List.of(id, id));
            case "literalEvidence" -> fixture.path("actualFailedPatch").path("changes").get(0).path("value");
            default -> json.createArrayNode();
        };
        assertThatThrownBy(() -> repair.apply(patch("/task/requirements/0/evidence", value))).isInstanceOf(ProviderException.class);
    }

    @Test
    void oldVersionKeepsOriginalCopyProtocolEvenForTheSameEngineeringFailure() throws Exception {
        var fixture = sourceReferenceFixture(); var raw = (ObjectNode) fixture.path("originalInput").deepCopy();
        raw.remove(List.of("taskSources", "inputBoundary", "fidelityDraft"));
        var input = json.treeToValue(raw, NaturalPromptInput.class).withStrategyVersion("62");
        var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), fixture.path("candidate"), input).orElseThrow();
        assertThat(repairData(repair).has("sourceReferences")).isFalse();
        assertThat(repair.instruction()).isEqualTo(NaturalPromptMetadataRepair.INSTRUCTION);
        var oldPatch = repair.apply(fixture.path("actualFailedPatch"));
        assertThatThrownBy(() -> json.treeToValue(oldPatch, NaturalPromptPhasePayloads.Enhancement.class)
                .toDraft(input, new com.promptoptimizer.enhancement.domain.ProviderMetadata("fixture", "fixture", false)))
                .isInstanceOf(ProviderException.class);
    }

    private JsonNode repairData(NaturalPromptMetadataRepair.Repair repair) throws Exception {
        String text = repair.userMessage(json); return json.readTree(text.substring(text.indexOf('{'))).path("protocolRepair");
    }

    @Test
    void sourceReferenceCannotBeBorrowedFromAnotherRequirementOrAnswer() throws Exception {
        var input = input().withStrategyVersion("63"); var body = candidate(input);
        ((ObjectNode) body.at("/task/requirements/0/evidence/0")).put("quote", "改写后的用户原句");
        ((ObjectNode) body.at("/task/requirements/2/evidence/0")).put("quote", "改写后的实际答案");
        var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), body, input).orElseThrow();
        var data = repairData(repair);
        String rawReference = data.path("referencePaths").path("/task/requirements/0/evidence").get(0).asText();
        assertThatThrownBy(() -> repair.apply(patch("/task/requirements/2/evidence", json.valueToTree(List.of(rawReference)))))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void oversizedReferenceCatalogFallsBackWithoutTruncationOrAutomaticSelection() throws Exception {
        String manyLines = java.util.stream.IntStream.range(0, 4097).mapToObj(i -> "不同原始来源行" + i).collect(java.util.stream.Collectors.joining("\n"));
        var context = new ContextSnapshot(manyLines, List.of(), List.of(), List.of(), List.of(), "COMPLETE", List.of(), List.of(), List.of(), "v3");
        var source = input(); var input = new NaturalPromptInput(source.rawPrompt(), context, source.actualAnswers(), source.conversation(), source.mandatoryRules(), source.modelId()).withStrategyVersion("63");
        var body = candidate(input);
        ((ObjectNode) body.at("/task/requirements/1/evidence/0")).put("quote", "不在原文里的说明");
        var repair = NaturalPromptMetadataRepair.inspect(json.valueToTree(input), body, input).orElseThrow();
        assertThat(repairData(repair).has("sourceReferences")).isFalse();
        assertThat(repair.instruction()).isEqualTo(NaturalPromptMetadataRepair.INSTRUCTION);
        assertThat(body.at("/task/requirements/1/evidence/0/quote").asText()).isEqualTo("不在原文里的说明");
    }

    /** 安全合成工程实际原件；没有账号凭据，不改变历史62失败，只有新协议在离线回放中使用63。 */
    private JsonNode sourceReferenceFixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/natural-metadata-repair/engineering62-source-reference.json")) {
            assertThat(stream).isNotNull(); return json.readTree(stream);
        }
    }

    private ObjectNode candidate(NaturalPromptInput input) {
        var response = json.createObjectNode().put("optimizedPrompt", "请根据背景制定方案，评估是否可靠，预算仍未知。").put("framework", "APE");
        var task = response.putObject("task").put("goal", "根据背景制定方案").put("deliverable", "方案").put("inputReadiness", "NEEDS_COMPLETION");
        var required = task.putArray("requirements");
        for (var source : input.taskSources()) {
            var item = required.addObject().put("text", source.text());
            item.putArray("dimensions").add(source.id().startsWith("actualAnswers") ? "USER_CONFIRMATION" : "GOAL");
            item.putArray("evidence").addObject().put("sourceId", source.id()).put("quote", source.text());
            item.putArray("bodyQuotes").add(response.path("optimizedPrompt").asText());
        }
        task.putArray("additions"); task.putArray("openDecisions"); return response;
    }

    private ObjectNode patch(String path, JsonNode value) {
        var patch = json.createObjectNode(); patch.putArray("changes").addObject().put("path", path).set("value", value); return patch;
    }
}
