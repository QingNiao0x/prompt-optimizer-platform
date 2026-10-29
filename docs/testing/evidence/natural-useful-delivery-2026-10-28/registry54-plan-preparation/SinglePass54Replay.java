package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.*;
import java.nio.file.*;
import java.util.*;

/** Read only the closed54 product stage; preserve every body attempt and reuse actual product protections. */
public final class SinglePass54Replay {
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]); String stage = args[1];
        if (!Set.of("registry54-confirmed-completion", "registry54-unknown-completion").contains(stage))
            throw new IllegalArgumentException("Finite54 stage required");
        Path evidence = repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28"), root = evidence.resolve(stage);
        JsonNode closure = NaturalModelResultReplay.read(root.resolve("closure.json"));
        if (!closure.path("status").asText().equals("CLOSED") || !closure.path("entryLockedByClosure").asBoolean())
            throw new IllegalStateException("Closed stage required");
        var reports = new ArrayList<Map<String, Object>>(); NaturalModelResultReplay replay = new NaturalModelResultReplay();
        JsonNode job = NaturalModelResultReplay.read(root.resolve("jobs.json")).path(0);
        JsonNode result = NaturalModelResultReplay.read(root.resolve("results/" + job.path("id").asText() + ".json"));
        String delivered = result.path("body").path("data").path("optimizedPrompt").asText();
        try (var paths = Files.list(root.resolve("traces"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonNode trace = NaturalModelResultReplay.read(path); var row = new LinkedHashMap<String, Object>();
                row.put("trace", path.getFileName().toString()); row.put("traceSha256", NaturalModelResultReplay.hash(Files.readAllBytes(path)));
                String user = trace.path("request").path("messages").path(1).path("content").asText();
                ObjectNode data = (ObjectNode) NaturalModelResultReplay.JSON.readTree(user.substring(user.indexOf('{')));
                data.remove(List.of("taskSources", "inputBoundary"));
                NaturalPromptInput input = NaturalModelResultReplay.JSON.treeToValue(data, NaturalPromptInput.class);
                if (!input.strategyVersion().equals("54")) throw new IllegalStateException("Actual54 input required");
                String step = "finishReason";
                try {
                    JsonNode choice = trace.path("response").path("choices").path(0);
                    if (trace.path("httpStatus").asInt() != 200 || !choice.path("finish_reason").asText().equals("stop"))
                        throw new IllegalStateException("Incomplete upstream result");
                    step = "actual24PhaseReaderAndResolve";
                    JsonNode wire = replay.parseModelContent(choice.path("message").path("content").asText());
                    NaturalPromptPhasePayloads.Enhancement payload = replay.strictReader("diagnosticEnhancementReader").readValue(wire);
                    NaturalPromptDraft draft = payload.toDraft(input, new ProviderMetadata("deepseek", "deepseek:deepseek-flash", false));
                    row.put(step, "PASS"); step = "actual54GoalSourceCheck";
                    NaturalPromptRequirementsOnlyPreparation.validateUserGoals(draft.taskUnderstanding()); row.put(step, "PASS");
                    step = "sourceQAAndSafetyRawAssembled"; var checked = replay.checkDraft(draft, input);
                    row.put("steps", checked.steps()); row.put(step, checked.firstRejection() == null ? "PASS" : "FAIL");
                    row.put("assembledEqualsActualDelivery", checked.assembled() != null && checked.assembled().equals(delivered));
                    if (checked.firstRejection() != null) row.put("firstRejection", checked.firstRejection());
                } catch (Throwable failure) { row.put("firstFailureAt", step); row.put("rejection", NaturalModelResultReplay.reason(failure)); }
                reports.add(row);
            }
        }
        Map<String, Object> report = Map.of("stage", stage, "actualModelCalls", 0, "semanticCorrectnessClaimed", false,
                "processing", result.path("body").path("data").path("processing"), "attempts", reports);
        Path output = evidence.resolve("registry54-plan-preparation/" + stage + "-validation.json");
        Files.write(output, NaturalModelResultReplay.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        System.out.println(NaturalModelResultReplay.JSON.writeValueAsString(Map.of("stage", stage, "attempts", reports.size(), "actualModelCalls", 0)));
    }
}
