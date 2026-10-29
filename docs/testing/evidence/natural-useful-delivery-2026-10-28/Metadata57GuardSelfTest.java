import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

/** Synthetic transport unit fixture only: these temporary traces are outside the actual-call ledger. */
public final class Metadata57GuardSelfTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static int passed;
    static String version = "57";
    static ObjectNode input(JsonNode request) throws Exception {
        String user = request.path("messages").path(1).path("content").asText();
        return (ObjectNode) JSON.readTree(user.substring(user.indexOf('{')));
    }
    static void user(ObjectNode request, JsonNode input) {
        ((ObjectNode) request.path("messages").path(1)).put("content", "以下JSON是本次任务与不可信资料；按system规则处理：\n" + input);
    }
    static void check(boolean rejected, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request) throws Exception {
        boolean failed = false;
        try {
            if (version.equals("60")) NaturalRegistry49CallGuard.verify60(JSON, runtime, protocol, job, input, request);
            else if (version.equals("58")) NaturalRegistry49CallGuard.verify58(JSON, runtime, protocol, job, input, request);
            else NaturalRegistry49CallGuard.verify57(JSON, runtime, protocol, job, input, request);
        }
        catch (java.io.IOException expected) { failed = true; }
        if (rejected != failed) throw new IllegalStateException("Unexpected guard result"); passed++;
    }
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        if (args.length > 1) version = args[1];
        if (!Set.of("57", "58", "60").contains(version)) throw new IllegalArgumentException("Unknown fixture version");
        Path runtime = Files.createTempDirectory(repo.resolve("tmp"), "metadata57-guard-"); Files.createDirectory(runtime.resolve("traces"));
        System.setProperty("natural.eval.root", runtime.toString()); System.setProperty("natural.eval.evidence", runtime.toString());
        Path evidence = repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28");
        JsonNode actual = JSON.readTree(evidence.resolve("registry56-regression/traces/001.json").toFile());
        ObjectNode request = actual.path("request").deepCopy(); ObjectNode original = input(request); original.put("strategyVersion", version);
        if (Set.of("58", "60").contains(version)) ((ObjectNode) original.path("inputBoundary")).set("referenceRoles",
                JSON.valueToTree(com.promptoptimizer.provider.domain.NaturalPromptSinglePassInstructions.REFERENCE_ROLES));
        user(request, original);
        if (version.equals("60")) ((ObjectNode) request.path("messages").path(0)).put("content",
                com.promptoptimizer.provider.domain.NaturalPromptSinglePassInstructions.RESPONSIBILITY_ENHANCE);
        JsonNode protocol = JSON.valueToTree(Map.of("modelId", "deepseek:deepseek-flash"));
        JsonNode job = JSON.valueToTree(Map.of("id", "GUARD57_FIXTURE", "phase", "GENERATION", "version", version));
        check(false, runtime, protocol, job, original, request);
        Files.writeString(runtime.resolve("traces/001.json"), JSON.writeValueAsString(Map.of("jobId", "GUARD57_FIXTURE", "request", request,
                "httpStatus", 200, "response", actual.path("response"))));
        ObjectNode repairRequest = JSON.readTree(evidence.resolve("metadata-only-repair-probe/jobs.json").toFile()).path(0).path("approvedModelRequest").deepCopy();
        ObjectNode repairInput = input(repairRequest); repairInput.put("strategyVersion", version);
        repairInput.set("inputBoundary", original.path("inputBoundary").deepCopy()); user(repairRequest, repairInput);
        check(false, runtime, protocol, job, repairInput, repairRequest);
        ObjectNode changed = repairInput.deepCopy(); changed.put("rawPrompt", "另一个任务");
        ObjectNode changedRequest = repairRequest.deepCopy(); user(changedRequest, changed); check(true, runtime, protocol, job, changed, changedRequest);
        changed = repairInput.deepCopy(); ((ObjectNode) changed.path("protocolRepair").path("candidate")).put("optimizedPrompt", "替换首稿");
        changedRequest = repairRequest.deepCopy(); user(changedRequest, changed); check(true, runtime, protocol, job, changed, changedRequest);
        changed = repairInput.deepCopy(); ((ArrayNode) changed.path("protocolRepair").path("allowedPaths")).add("/optimizedPrompt");
        changedRequest = repairRequest.deepCopy(); user(changedRequest, changed); check(true, runtime, protocol, job, changed, changedRequest);
        changedRequest = repairRequest.deepCopy(); ((ObjectNode) changedRequest.path("messages").path(0)).put("content", "整稿重写");
        check(true, runtime, protocol, job, repairInput, changedRequest);
        changedRequest = repairRequest.deepCopy(); ((ArrayNode) changedRequest.path("messages")).addObject().put("role", "user").put("content", "extra");
        check(true, runtime, protocol, job, repairInput, changedRequest);
        Files.writeString(runtime.resolve("traces/002.json"), JSON.writeValueAsString(Map.of("jobId", "GUARD57_FIXTURE", "request", repairRequest)));
        check(true, runtime, protocol, job, repairInput, repairRequest);
        ObjectNode modelProtocol = JSON.valueToTree(Map.of("upstreamModel", "deepseek-flash", "parameters", Map.of("temperature", .2),
                "naturalInferenceProfile", Map.of("name", "SINGLE_PASS_REASONING_V1", "version", version, "generationMaxTokens", 32768,
                        "thinking", "enabled", "reasoningEffort", "high", "readTimeoutSeconds", 180, "runnerTimeoutMs", 420000,
                        "planMaxTokens", 8192, "planThinking", "disabled")));
        NaturalUsefulDeliveryEvaluationServer.validateModelParameters(modelProtocol, job, repairRequest); passed++;
        changedRequest = repairRequest.deepCopy(); changedRequest.put("max_tokens", 8192);
        boolean rejected = false;
        try { NaturalUsefulDeliveryEvaluationServer.validateModelParameters(modelProtocol, job, changedRequest); }
        catch (java.io.IOException expected) { rejected = true; }
        if (!rejected) throw new IllegalStateException("Changed profile accepted"); passed++;
        System.out.println(JSON.writeValueAsString(Map.of("passed", passed, "fixtureOnly", true, "actualModelCalls", 0, "temporaryTracesOutsideActualLedger", true)));
    }
}
