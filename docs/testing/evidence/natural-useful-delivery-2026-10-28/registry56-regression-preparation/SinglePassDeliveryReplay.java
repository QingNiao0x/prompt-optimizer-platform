package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.*;
import java.nio.file.*;
import java.util.*;

/** Locate a closed single-pass rejection; diagnostic omissions are reported, never adopted as successful delivery. */
public final class SinglePassDeliveryReplay {
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]), root = repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28/registry56-regression");
        var replay = new NaturalModelResultReplay(); var rows = new ArrayList<Map<String, Object>>();
        for (String name : List.of("001.json", "002.json", "003.json", "004.json", "005.json", "006.json", "007.json", "008.json")) {
            JsonNode trace = NaturalModelResultReplay.read(root.resolve("traces/" + name));
            var row = new LinkedHashMap<String, Object>(); row.put("trace", name); row.put("jobId", trace.path("jobId").asText());
            String step = "actualReaderAndResolve";
            try {
                String user = trace.path("request").path("messages").path(1).path("content").asText();
                ObjectNode data = (ObjectNode) NaturalModelResultReplay.JSON.readTree(user.substring(user.indexOf('{')));
                data.remove(List.of("taskSources", "inputBoundary"));
                NaturalPromptInput input = NaturalModelResultReplay.JSON.treeToValue(data, NaturalPromptInput.class);
                var wire = replay.parseModelContent(trace.path("response").path("choices").path(0).path("message").path("content").asText());
                NaturalPromptPhasePayloads.Enhancement payload = replay.strictReader("diagnosticEnhancementReader").readValue(wire);
                NaturalPromptDraft draft = payload.toDraft(input, new ProviderMetadata("deepseek", "deepseek:deepseek-flash", false));
                row.put(step, "PASS"); step = "originalGoalRoleGuard";
                try { NaturalPromptRequirementsOnlyPreparation.validateUserGoals(draft.taskUnderstanding()); row.put(step, "PASS"); }
                catch (Throwable failure) { row.put(step, "FAIL"); row.put("goalRejection", NaturalModelResultReplay.reason(failure)); }
                // Deliberately separate this diagnostic from the original guard outcome; do not alter any model content.
                var checked = replay.checkDraft(draft, input); row.put("remainingOriginalChecks", checked.steps());
                row.put("remainingChecksPassed", checked.firstRejection() == null);
                row.put("assemblyChangedBody", checked.assembled() != null && !checked.assembled().equals(draft.optimizedPrompt()));
                row.put("goalsAndOrigins", draft.taskUnderstanding().requirements().stream()
                        .filter(r -> r.dimensions().contains(NaturalTaskUnderstanding.Dimension.GOAL))
                        .map(r -> Map.of("text", r.text(), "origins", r.evidence().stream().map(e -> e.origin().name()).toList())).toList());
            } catch (Throwable failure) { row.put("firstFailureAt", step); row.put("rejection", NaturalModelResultReplay.reason(failure)); }
            rows.add(row);
        }
        var report = Map.of("actualModelCalls", 0, "originalDeliveryStatusesUnchanged", true, "modelContentModified", false,
                "semanticCorrectnessClaimed", false, "attempts", rows);
        Path output = repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28/registry56-regression-preparation/rejection-diagnosis.json");
        Files.write(output, NaturalModelResultReplay.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        System.out.println(NaturalModelResultReplay.JSON.writeValueAsString(report));
    }
}
