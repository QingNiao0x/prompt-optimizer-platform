package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalPromptReferencedEnhancement;
import com.promptoptimizer.provider.domain.NaturalPromptRequirementRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Minimal actual-reader routing check using existing closed50 bytes in memory; no old result is reclassified. */
public final class Registry51ReplaySmoke {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static int passed;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path old = root.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28/registry50-development/traces");
        var constructor = NaturalRegistry49ResultReplay.class.getDeclaredConstructor(); constructor.setAccessible(true);
        var adapter = constructor.newInstance();
        JsonNode preparationTrace = JSON.readTree(old.resolve("001.json").toFile());
        JsonNode preparation = content(preparationTrace);
        JsonNode firstWriter = content(JSON.readTree(old.resolve("002.json").toFile()));
        JsonNode secondWriter = content(JSON.readTree(old.resolve("003.json").toFile()));
        ObjectReader old49 = adapter.writerReaderForVersion("49"), old50 = adapter.writerReaderForVersion("50");
        ObjectReader next51 = adapter.writerReaderForVersion("51");
        reject(old49, secondWriter); reject(old50, secondWriter);
        NaturalPromptReferencedEnhancement response = next51.readValue(secondWriter); passed++;
        var shared = new NaturalModelResultReplay();
        ObjectReader strictPreparation = shared.strictReader("requirementPreparationReader");
        ObjectNode wrongPreparation = preparation.deepCopy(); wrongPreparation.put("bodyQuotes_note", ""); reject(strictPreparation, wrongPreparation);
        String text = preparationTrace.path("request").path("messages").path(1).path("content").asText();
        ObjectNode input = (ObjectNode) JSON.readTree(text.substring(text.indexOf('{')));
        input.retain(List.of("rawPrompt", "context", "actualAnswers", "conversation", "mandatoryRules", "modelId", "planningContext", "strategyVersion"));
        input.put("strategyVersion", "51");
        NaturalPromptInput actual = JSON.treeToValue(input, NaturalPromptInput.class);
        NaturalPromptRequirementRegistry.Prepared prepared = ((NaturalPromptRequirementRegistry.Preparation) strictPreparation.readValue(preparation)).resolve(actual); passed++;
        // Required bodyQuotes are enforced by the actual normalize boundary, not merely by DTO deserialization.
        NaturalPromptReferencedEnhancement incomplete = next51.readValue(firstWriter);
        try { incomplete.normalize(actual, new ProviderMetadata("offline", "offline", false), prepared);
            throw new AssertionError("Missing authoritative bodyQuotes were accepted by normalization"); }
        catch (com.promptoptimizer.provider.domain.ProviderException expected) { passed++; }
        var normalized = response.normalize(actual, new ProviderMetadata("offline", "offline", false), prepared); passed++;
        var check = shared.checkDraft(normalized.draft(), actual);
        if (check.firstRejection() != null) throw new AssertionError("Actual51 application checks rejected the controlled existing candidate"); passed++;
        System.out.println(JSON.writeValueAsString(Map.of("passed", passed, "failed", 0, "actualModelCalls", 0,
                "source", "ClosedFlash50Q01 bytes cloned only in memory; not actual51 model output or a changed old verdict",
                "actual51Reader", "referencedDiagnosticEnhancementReader", "preparationReader", "requirementPreparationReader strict",
                "rawAndAssembledApplicationChecks", "PASS_PROTOCOL_ONLY_NOT_SEMANTIC_QUALITY")));
    }

    private static JsonNode content(JsonNode trace) throws Exception {
        return JSON.readTree(trace.path("response").path("choices").path(0).path("message").path("content").asText());
    }

    private static void reject(ObjectReader reader, JsonNode body) throws Exception {
        try { reader.readValue(body); throw new AssertionError("Protected writer/preparation boundary was accepted"); }
        catch (java.io.IOException expected) { passed++; }
    }
}
