package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.*;
import java.nio.file.*;
import java.util.*;

/** Offline replay of the frozen ON/OFF final content; reasoning text is never read or exported. */
public final class SinglePassThinkingReplay {
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path root = repo.resolve("tmp/natural-useful-delivery-2026-10-28/single-pass54-thinking-probe");
        var replay = new NaturalModelResultReplay(); var reports = new ArrayList<Map<String, Object>>();
        for (String name : List.of("001.json", "002.json")) {
            JsonNode trace = NaturalModelResultReplay.read(root.resolve("traces/" + name));
            var row = new LinkedHashMap<String, Object>(); row.put("jobId", trace.path("jobId").asText());
            String step = "completedResponse";
            try {
                JsonNode choice = trace.path("response").path("choices").path(0);
                if (trace.path("httpStatus").asInt() != 200 || !choice.path("finish_reason").asText().equals("stop"))
                    throw new IllegalStateException("Incomplete response");
                String user = trace.path("request").path("messages").path(1).path("content").asText();
                ObjectNode data = (ObjectNode) NaturalModelResultReplay.JSON.readTree(user.substring(user.indexOf('{')));
                data.remove(List.of("taskSources", "inputBoundary"));
                NaturalPromptInput input = NaturalModelResultReplay.JSON.treeToValue(data, NaturalPromptInput.class);
                step = "actualPhaseReaderAndResolve";
                var wire = replay.parseModelContent(choice.path("message").path("content").asText());
                NaturalPromptPhasePayloads.Enhancement payload = replay.strictReader("diagnosticEnhancementReader").readValue(wire);
                NaturalPromptDraft draft = payload.toDraft(input, new ProviderMetadata("deepseek", "deepseek:deepseek-flash", false));
                row.put(step, "PASS"); step = "goalUserSource";
                NaturalPromptRequirementsOnlyPreparation.validateUserGoals(draft.taskUnderstanding()); row.put(step, "PASS");
                step = "commonSourceAnswerSafetyAndBodyChecks";
                var checked = replay.checkDraft(draft, input); row.put("steps", checked.steps());
                row.put(step, checked.firstRejection() == null ? "PASS" : "FAIL");
                row.put("assemblyChangedBody", checked.assembled() != null && !checked.assembled().equals(draft.optimizedPrompt()));
                if (checked.firstRejection() != null) row.put("firstRejection", checked.firstRejection());
            } catch (Throwable failure) { row.put("firstFailureAt", step); row.put("rejection", NaturalModelResultReplay.reason(failure)); }
            reports.add(row);
        }
        var report = Map.of("actualModelCalls", 0, "runtimeVersion", "54", "semanticCorrectnessClaimed", false, "attempts", reports);
        Path output = repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28/single-pass54-thinking-probe/strict-replay.json");
        Files.write(output, NaturalModelResultReplay.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        System.out.println(NaturalModelResultReplay.JSON.writeValueAsString(report));
    }
}
