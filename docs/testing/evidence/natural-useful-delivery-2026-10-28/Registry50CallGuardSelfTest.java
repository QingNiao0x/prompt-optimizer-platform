import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalPromptRequirementRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Only future50 activation and version separation; shared49 behavior was checked previously. No product HTTP or model. */
public final class Registry50CallGuardSelfTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static int passed;

    /** Exercise the real shared reader/resolve through synthetic traces in a private temporary fixture. */
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path fixture = Files.createTempDirectory(repo.resolve("tmp"), "registry50-guard-check-");
        JsonNode seed = JSON.readTree(repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28/registry49-development/traces/001.json").toFile());
        String user = seed.path("request").path("messages").path(1).path("content").asText();
        ObjectNode original = (ObjectNode) JSON.readTree(user.substring(user.indexOf('{')));
        original.remove(List.of("taskScope", "preparedRequirements", "taskSources", "inputBoundary"));
        original.put("strategyVersion", "50");
        NaturalPromptInput input = JSON.treeToValue(original, NaturalPromptInput.class);
        ObjectNode core = JSON.valueToTree(input);
        core.putObject("inputBoundary").put("stage", "PROMPT_OPTIMIZATION").put("contextMeaning", "Same complete input");
        ObjectNode protocol = JSON.createObjectNode().put("modelId", "deepseek:deepseek-flash");
        ObjectNode job = JSON.createObjectNode().put("id", "T50_GEN").put("phase", "GENERATION").put("version", "50");
        ObjectNode preparation = JSON.createObjectNode();
        preparation.set("actions", JSON.valueToTree(List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "",
                "evidence", List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()))))));
        preparation.set("response", JSON.valueToTree(Map.of("basis", "UNSPECIFIED", "description", "", "evidence", List.of())));
        preparation.set("requirements", JSON.valueToTree(List.of(Map.of("text", input.rawPrompt(), "dimensions", List.of("GOAL"),
                "evidence", List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()))))));
        var prepared = JSON.treeToValue(preparation, NaturalPromptRequirementRegistry.Preparation.class).resolve(input);
        ObjectNode writer = core.deepCopy();
        writer.set("taskScope", JSON.valueToTree(prepared.scope())); writer.set("preparedRequirements", JSON.valueToTree(prepared.requirements()));
        Path runtime = fixture.resolve("runtime"); Files.createDirectories(runtime.resolve("traces"));
        check(NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, core).callStage().equals("REQUIREMENT_PREPARATION"));
        writeTrace(runtime, "001", core, preparation, "stop");
        check(NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, writer).callStage().equals("BODY_GENERATION"));
        ObjectNode changed = writer.deepCopy(); changed.put("strategyVersion", "49"); reject(runtime, protocol, job, changed);
        changed = job.deepCopy(); changed.put("version", "49"); reject(runtime, protocol, changed, writer);
        changed = job.deepCopy(); changed.put("version", "51"); reject(runtime, protocol, changed, writer);
        changed = writer.deepCopy(); ((ObjectNode) changed.path("preparedRequirements").path(0)).put("reqId", "r99"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); ((ObjectNode) changed.path("preparedRequirements").path(0).path("evidence").path(0)).put("quote", "unapproved"); reject(runtime, protocol, job, changed);
        changed = writer.deepCopy(); changed.put("modelId", "unapproved:model"); reject(runtime, protocol, job, changed);
        writeTrace(runtime, "002", core, preparation, null); reject(runtime, protocol, job, writer);
        check(NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, core).callStage().equals("REQUIREMENT_PREPARATION"));
        try (var paths = Files.list(runtime.resolve("guard-events"))) {
            for (Path path : paths.toList()) {
                JsonNode event = JSON.readTree(path.toFile());
                check(event.size() == 7 && event.path("actualModelCalls").asInt(-1) == 0
                        && !event.path("upstreamRequestSent").asBoolean(true));
            }
        }
        System.out.println(JSON.writeValueAsString(Map.of("passed", passed, "failed", 0, "modelCalls", 0,
                "fixtureDirectory", fixture.toString(), "syntheticPreparationNotRealExtraction", true,
                "testScope", "Future50 finite activation/version mismatch and strict shared registry guard only")));
    }

    private static void writeTrace(Path runtime, String number, JsonNode core, JsonNode preparation, String finish) throws Exception {
        ObjectNode choice = JSON.createObjectNode();
        if (finish != null) choice.put("finish_reason", finish);
        choice.putObject("message").put("content", JSON.writeValueAsString(preparation));
        JSON.writeValue(runtime.resolve("traces/" + number + ".json").toFile(), Map.of("jobId", "T50_GEN", "httpStatus", 200,
                "request", Map.of("messages", List.of(Map.of("role", "system", "content", "Offline shared-schema fixture"),
                        Map.of("role", "user", "content", "Original task JSON:\n" + JSON.writeValueAsString(core)))),
                "response", Map.of("choices", List.of(choice))));
    }

    private static void reject(Path runtime, JsonNode protocol, JsonNode job, JsonNode input) throws Exception {
        try { NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, input); throw new AssertionError("Unexpected boundary acceptance"); }
        catch (java.io.IOException expected) { passed++; }
    }

    private static void check(boolean value) { if (!value) throw new AssertionError("Future50 guard expectation failed"); passed++; }
}
