package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.*;
import java.nio.file.*;
import java.util.*;

/** Use the actual first rejected drafts; no quality-based candidate selection or product adoption. */
public final class MetadataRepairProbe {
    public static void main(String[] args) throws Throwable {
        Path repo = Path.of(args[0]), evidence = repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28");
        Path target = evidence.resolve("metadata-only-repair-probe"); Files.createDirectories(target);
        var replay = new NaturalModelResultReplay(); var jobs = new ArrayList<Map<String, Object>>(); var reports = new ArrayList<Map<String, Object>>();
        for (String number : List.of("001", "007")) {
            String caseId = number.equals("001") ? "U02" : "V02";
            Path sourcePath = evidence.resolve("registry56-regression/traces/" + number + ".json");
            var trace = NaturalModelResultReplay.read(sourcePath); var request = (ObjectNode) trace.path("request").deepCopy();
            String text = request.path("messages").path(1).path("content").asText();
            var original = (ObjectNode) NaturalModelResultReplay.JSON.readTree(text.substring(text.indexOf('{')));
            ObjectNode data = original.deepCopy(); data.remove(List.of("taskSources", "inputBoundary"));
            var input = NaturalModelResultReplay.JSON.treeToValue(data, NaturalPromptInput.class);
            var candidate = replay.parseModelContent(trace.path("response").path("choices").path(0).path("message").path("content").asText());
            var repair = NaturalPromptMetadataRepair.inspect(original, candidate, input).orElseThrow();
            var row = new LinkedHashMap<String, Object>(); row.put("caseId", caseId); row.put("issues", repair.issues());
            row.put("firstDraftBodySha256", NaturalModelResultReplay.hash(candidate.path("optimizedPrompt").asText()));
            row.put("firstTraceSha256", NaturalModelResultReplay.hash(Files.readAllBytes(sourcePath)));
            if (args[1].equals("PREPARE")) {
                ((ObjectNode) request.path("messages").path(0)).put("content", NaturalPromptMetadataRepair.INSTRUCTION);
                ((ObjectNode) request.path("messages").path(1)).put("content", repair.userMessage(NaturalModelResultReplay.JSON));
                jobs.add(Map.of("id", caseId + "_META_PATCH", "caseId", caseId, "phase", "EXECUTION", "version", "56",
                        "purpose", "METADATA_ONLY_FIRST_DRAFT_REPAIR", "approvedModelRequest", request));
            } else {
                var result = NaturalModelResultReplay.read(repo.resolve("tmp/natural-useful-delivery-2026-10-28/metadata-only-repair-probe/traces/" + (number.equals("001") ? "001" : "002") + ".json"));
                try {
                    if (result.path("httpStatus").asInt() != 200 || !result.path("response").path("choices").path(0).path("finish_reason").asText().equals("stop"))
                        throw new IllegalStateException("Incomplete mechanism result");
                    JsonNode patch = replay.parseModelContent(result.path("response").path("choices").path(0).path("message").path("content").asText());
                    JsonNode fixed = repair.apply(patch); row.put("patch", patch);
                    row.put("bodyUnchanged", candidate.path("optimizedPrompt").equals(fixed.path("optimizedPrompt")));
                    NaturalPromptPhasePayloads.Enhancement payload = replay.strictReader("diagnosticEnhancementReader").readValue(fixed);
                    var draft = payload.toDraft(input, new ProviderMetadata("deepseek", "deepseek:deepseek-flash", false));
                    NaturalPromptRequirementsOnlyPreparation.validateUserGoals(draft.taskUnderstanding());
                    var checked = replay.checkDraft(draft, input); row.put("remainingChecks", checked.steps());
                    row.put("allOriginalProtectionsPassed", checked.firstRejection() == null);
                    row.put("assemblyChangedBody", checked.assembled() != null && !checked.assembled().equals(draft.optimizedPrompt()));
                } catch (Throwable failure) { row.put("rejection", NaturalModelResultReplay.reason(failure)); }
            }
            reports.add(row);
        }
        Map<String, Object> report = Map.of("actualModelCallsFromThisTool", 0, "mode", args[1], "productVersionUnchanged", true,
                "historicalFailuresUnchanged", true, "semanticQualityClaimed", false, "cases", reports);
        Files.write(target.resolve(args[1].equals("PREPARE") ? "preparation.json" : "replay.json"),
                NaturalModelResultReplay.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        if (args[1].equals("PREPARE")) Files.write(target.resolve("jobs.json"), NaturalModelResultReplay.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(jobs), StandardOpenOption.CREATE_NEW);
        System.out.println(NaturalModelResultReplay.JSON.writeValueAsString(report));
    }
}
