import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalPromptRequirementRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Synthetic transport fixtures use actual49 readers and normalization; no Spring server or model is started. */
public final class Registry49CallGuardSelfTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static int passed;

    /** Check scope/body linkage, original-input preservation and boundary failures entirely offline. */
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path fixture = Files.createTempDirectory(repo.resolve("tmp"), "registry49-guard-check-");
        Path runtime = fixture.resolve("runtime"); Files.createDirectories(runtime.resolve("traces"));
        JsonNode seed = JSON.readTree(repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28/writer-thinking-preparation/requests/Q01.json").toFile());
        String user = seed.path("messages").path(1).path("content").asText();
        ObjectNode original = (ObjectNode) JSON.readTree(user.substring(user.indexOf('{')));
        original.remove(List.of("taskScope", "preparedRequirements", "taskSources", "inputBoundary"));
        String raw = "请先了解现有安排并等待确认，不要新增交付文件。";
        original.put("rawPrompt", raw).put("strategyVersion", "49");
        original.set("actualAnswers", JSON.valueToTree(List.of(new PlanAnswer("q1", "是否允许今天", "允许今天"))));
        NaturalPromptInput input = JSON.treeToValue(original, NaturalPromptInput.class);
        ObjectNode core = JSON.valueToTree(input);
        core.putObject("inputBoundary").put("stage", "PROMPT_OPTIMIZATION").put("contextMeaning", "Same original input in both calls");
        ObjectNode protocol = JSON.createObjectNode(); protocol.put("modelId", "deepseek:deepseek-flash");
        ObjectNode job = JSON.createObjectNode(); job.put("id", "T49_GEN").put("phase", "GENERATION").put("version", "49");
        ObjectNode preparation = JSON.createObjectNode();
        preparation.set("actions", JSON.valueToTree(List.of(Map.of("text", "了解安排并等待", "timing", "CURRENT", "condition", "",
                "evidence", List.of(Map.of("sourceId", "rawPrompt", "quote", raw))))));
        preparation.set("response", JSON.valueToTree(Map.of("basis", "UNSPECIFIED", "description", "", "evidence", List.of())));
        preparation.set("requirements", JSON.valueToTree(List.of(
                Map.of("text", raw, "dimensions", List.of("GOAL", "CONDITION"), "evidence", List.of(Map.of("sourceId", "rawPrompt", "quote", raw))),
                Map.of("text", "保留真实回答允许今天", "dimensions", List.of("USER_CONFIRMATION"), "evidence", List.of(Map.of("sourceId", "actualAnswers.0", "quote", "允许今天"))))));
        var prepared = JSON.treeToValue(preparation, NaturalPromptRequirementRegistry.Preparation.class).resolve(input);
        ObjectNode writer = core.deepCopy(); writer.set("taskScope", JSON.valueToTree(prepared.scope())); writer.set("preparedRequirements", JSON.valueToTree(prepared.requirements()));
        check(NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, core).callStage().equals("REQUIREMENT_PREPARATION"));
        reject(runtime, protocol, job, writer);
        writeTrace(runtime, "001", "T49_GEN", core, JSON.writeValueAsString(preparation));
        var audit = NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, writer);
        check(audit.callStage().equals("BODY_GENERATION") && audit.preparedTrace().equals("001.json") && audit.preparedRequirementCount() == 2);
        ObjectNode changed = writer.deepCopy(); ((ObjectNode) changed.path("preparedRequirements").path(0)).put("reqId", "r99"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); ((ObjectNode) changed.path("preparedRequirements").path(0).path("evidence").path(0)).put("sourceId", "foreign-source"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); ((ObjectNode) changed.path("preparedRequirements").path(0).path("evidence").path(0)).put("quote", "invented quote"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); ((ObjectNode) changed.path("preparedRequirements").path(0).path("evidence").path(0)).put("origin", "BOUND_PLAN_ANSWER"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); changed.putArray("preparedRequirements"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); changed.withArray("mandatoryRules").add("New unapproved operation"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); ((ObjectNode) changed.path("actualAnswers").path(0)).put("answer", "不允许今天"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); ((ObjectNode) changed.path("context")).put("customDescription", "New unapproved material"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); changed.put("modelId", "different:route"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); changed.put("endpoint", "https://unapproved.invalid"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); ((ObjectNode) changed.path("taskSources").path(0)).put("kind", "BOUND_PLAN_ANSWER"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); ((ObjectNode) changed.path("taskScope").path("response")).put("description", "New document obligation"); reject(runtime, protocol, job, changed);
        // A malformed preparation is still allowed to reach product repair. This helper never modifies its response.
        writeTrace(runtime, "002", "T49_GEN", core, "{malformed");
        byte[] badOriginal = Files.readAllBytes(runtime.resolve("traces/002.json"));
        check(NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, core).callStage().equals("REQUIREMENT_PREPARATION"));
        reject(runtime, protocol, job, writer);
        check(java.util.Arrays.equals(badOriginal, Files.readAllBytes(runtime.resolve("traces/002.json"))));
        // The most recent actual preparation is authoritative for the derived registry; never choose a better older one.
        writeTrace(runtime, "003", "T49_GEN", core, JSON.writeValueAsString(preparation));
        check(NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, writer).preparedTrace().equals("003.json"));
        writeTrace(runtime, "004", "T49_GEN", writer, "{writer failure}");
        check(NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, writer).callStage().equals("BODY_GENERATION"));
        reject(runtime, protocol, job, core);
        Path foreign = fixture.resolve("foreign-runtime"); Files.createDirectories(foreign.resolve("traces"));
        writeTrace(foreign, "001", "OTHER_JOB", core, JSON.writeValueAsString(preparation));
        reject(foreign, protocol, job, writer);
        Path missingFinish = fixture.resolve("missing-finish-runtime"); Files.createDirectories(missingFinish.resolve("traces"));
        writeTrace(missingFinish, "001", "T49_GEN", core, JSON.writeValueAsString(preparation));
        ObjectNode missingFinishTrace = (ObjectNode) JSON.readTree(missingFinish.resolve("traces/001.json").toFile());
        ((ObjectNode) missingFinishTrace.path("response").path("choices").path(0)).remove("finish_reason");
        JSON.writeValue(missingFinish.resolve("traces/001.json").toFile(), missingFinishTrace);
        reject(missingFinish, protocol, job, writer);
        for (Path root : List.of(runtime, foreign, missingFinish)) {
            try (var events = Files.list(root.resolve("guard-events"))) {
                for (Path path : events.toList()) {
                    JsonNode event = JSON.readTree(path.toFile());
                    if (event.path("actualModelCalls").asInt(-1) != 0 || event.path("upstreamRequestSent").asBoolean(true)
                            || !event.path("code").asText().matches("GUARD49_[A-Z_]+") || event.size() != 7)
                        throw new AssertionError("Unsafe boundary event");
                }
            }
        }
        check(true);
        System.out.println(JSON.writeValueAsString(Map.of("passed", passed, "failed", 0, "modelCalls", 0,
                "fixtureDirectory", fixture.toString(), "syntheticPreparationOnlyNotRealExtractionEvidence", true)));
    }

    private static void writeTrace(Path runtime, String number, String jobId, JsonNode input, String response) throws Exception {
        JSON.writeValue(runtime.resolve("traces/" + number + ".json").toFile(), Map.of("jobId", jobId, "httpStatus", 200,
                "request", Map.of("messages", List.of(Map.of("role", "system", "content", "Controlled offline JSON fixture"),
                        Map.of("role", "user", "content", "Original task JSON:\n" + JSON.writeValueAsString(input)))),
                "response", Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", response))))));
    }

    private static void reject(Path runtime, JsonNode protocol, JsonNode job, JsonNode input) throws Exception {
        try { NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, input); throw new AssertionError("Invalid49 boundary accepted"); }
        catch (java.io.IOException expected) { passed++; }
    }

    private static void check(boolean value) { if (!value) throw new AssertionError("Guard expectation failed"); passed++; }
}
