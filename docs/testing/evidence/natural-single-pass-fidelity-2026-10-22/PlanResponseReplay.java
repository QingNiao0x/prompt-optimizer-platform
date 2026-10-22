import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.*;
import java.nio.file.*;
import java.util.*;

/**
 * Replays the two captured synthetic Plan-final responses using the unchanged domain validators.
 * Arguments: public transports.jsonl, output JSON. Uses API/runtime dependencies only; no client,
 * account, credentials, external service or model call is created. Transport line N corresponds
 * to the original zero-padded trace N.json. Run with the API dependency/runtime classpath.
 */
class PlanResponseReplay {
    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var records = new ArrayList<Map<String, Object>>();
        var lines = Files.readAllLines(Path.of(args[0]));
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.isBlank()) continue;
            JsonNode trace = json.readTree(line);
            if (!trace.path("jobId").asText().equals("PLAN_MAINTENANCE_V4_PLAN_GEN")) continue;
            String user = trace.path("request").path("messages").path(1).path("content").asText();
            ObjectNode payload = (ObjectNode) json.readTree(user.substring(user.indexOf('{')));
            // Constructor derives authoritative sources from the original input/actual answers.
            payload.remove(List.of("taskSources", "completionSuggestions", "fidelityDraft"));
            NaturalPromptInput input = json.treeToValue(payload, NaturalPromptInput.class);
            String content = trace.path("response").path("choices").path(0).path("message").path("content").asText().strip();
            String fence = Character.toString(96).repeat(3);
            if (content.startsWith(fence + "json\n") && content.endsWith(fence)) {
                content = content.substring(8, content.length() - 3).strip();
            }
            JsonNode body = json.readTree(content);
            var item = new LinkedHashMap<String, Object>();
            item.put("trace", String.format(Locale.ROOT, "%03d.json", i + 1));
            item.put("actualAnswerSources", input.taskSources().stream()
                    .filter(s -> s.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER)
                    .map(NaturalTaskSource::id).toList());
            String stage = "resolve";
            try {
                var task = json.treeToValue(body.path("taskUnderstanding"), NaturalTaskUnderstanding.class).resolve(input);
                stage = "validateEnhancement";
                task.review().validateEnhancement(task, body.path("optimizedPrompt").asText().strip());
                item.put("accepted", true);
            } catch (ProviderException failure) {
                item.put("accepted", false);
                item.put("stage", stage);
                item.put("failureClass", failure.getClass().getSimpleName());
                item.put("failureType", failure.getFailureType().name());
                item.put("message", failure.getMessage());
            }
            item.put("planImpactCount", body.path("taskUnderstanding").path("review").path("planImpacts").size());
            records.add(item);
        }
        Files.writeString(Path.of(args[1]), json.writerWithDefaultPrettyPrinter().writeValueAsString(records));
        System.out.println("Replayed " + records.size() + " captured responses; no model calls.");
    }
}
