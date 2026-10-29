import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Offline guard checks only: no Spring startup, identity changes or network transport. */
public final class WriterThinkingProfileSelfTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int passed;

    /** Run self-contained filesystem and transport-policy fixtures without starting the product server. */
    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory(Path.of(args[0]), "writer-thinking-java-check-");
        Path evidence = fixture.resolve("evidence/stage");
        Path runtime = fixture.resolve("runtime/stage");
        Files.createDirectories(evidence.resolve("writer-sources"));
        Files.createDirectories(runtime.resolve("traces"));
        System.setProperty("natural.eval.evidence", evidence.toString());
        System.setProperty("natural.eval.root", runtime.toString());
        ObjectNode original = JSON.createObjectNode();
        original.put("model", "deepseek-flash").put("temperature", 0.2).put("max_tokens", 8192).put("stream", false);
        original.set("thinking", JSON.valueToTree(Map.of("type", "disabled")));
        original.set("response_format", JSON.valueToTree(Map.of("type", "json_object")));
        original.set("messages", JSON.valueToTree(java.util.List.of(Map.of("role", "system", "content", "Return JSON"),
                Map.of("role", "user", "content", "{\"strategyVersion\":\"48\",\"taskScope\":{}}"))));
        byte[] source = JSON.writeValueAsBytes(original);
        Files.write(evidence.resolve("writer-sources/T01.json"), source);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        ObjectNode protocol = JSON.createObjectNode();
        protocol.put("status", "FROZEN").put("taskMaximumActualCalls", 500).put("maximumNewActualCalls", 6)
                .put("upstreamModel", "deepseek-flash").put("experimentProfile", "WRITER_THINKING_ABLATION_V1");
        protocol.set("parameters", JSON.valueToTree(Map.of("temperature", 0.2, "generationMaxTokens", 8192, "executionMaxTokens", 32768)));
        protocol.set("phaseLimits", JSON.valueToTree(Map.of("GENERATION", 0, "PLAN", 0, "EXECUTION", 6, "REVIEW", 0)));
        protocol.set("writerThinkingProfile", JSON.valueToTree(Map.of("name", "WRITER_THINKING_ABLATION_V1", "maximumActualCalls", 6,
                "writerMaxTokens", 32768, "readTimeoutSeconds", 300, "runnerTimeoutMs", 360000,
                "protocolRepairsAllowed", false, "qualityRetriesAllowed", false,
                "writerSources", java.util.List.of(Map.of("caseId", "T01", "snapshot", "writer-sources/T01.json", "sha256", hash)))));
        ObjectNode off = original.deepCopy(); off.put("max_tokens", 32768);
        ObjectNode on = off.deepCopy(); on.set("thinking", JSON.valueToTree(Map.of("type", "enabled"))); on.put("reasoning_effort", "high");
        ObjectNode job = JSON.createObjectNode();
        job.put("id", "T01_OFF").put("caseId", "T01").put("phase", "EXECUTION").put("version", "48")
                .put("purpose", "WRITER_THINKING_ABLATION").put("thinkingArm", "OFF"); job.set("approvedModelRequest", off);
        NaturalUsefulDeliveryEvaluationServer.validateModelParameters(protocol, job, off); passed++;
        ObjectNode onJob = job.deepCopy(); onJob.put("id", "T01_ON").put("thinkingArm", "ON"); onJob.set("approvedModelRequest", on);
        NaturalUsefulDeliveryEvaluationServer.validateModelParameters(protocol, onJob, on); passed++;
        ObjectNode changed = on.deepCopy(); changed.put("reasoning_effort", "low"); reject(protocol, onJob, changed);
        changed = off.deepCopy(); changed.put("reasoning_effort", "none"); reject(protocol, job, changed);
        changed = on.deepCopy(); changed.put("max_tokens", 8192); reject(protocol, onJob, changed);
        changed = on.deepCopy(); ((ObjectNode) changed.path("messages").path(0)).put("content", "Changed JSON guidance"); reject(protocol, onJob, changed);
        changed = on.deepCopy(); changed.remove("response_format"); reject(protocol, onJob, changed);
        ObjectNode otherJob = onJob.deepCopy(); otherJob.put("phase", "GENERATION"); reject(protocol, otherJob, on);
        otherJob = onJob.deepCopy(); otherJob.put("purpose", "DOWNSTREAM_WORK"); reject(protocol, otherJob, on);
        ObjectNode changedProtocol = protocol.deepCopy(); changedProtocol.put("experimentProfile", "UNKNOWN"); reject(changedProtocol, onJob, on);
        changedProtocol = protocol.deepCopy(); changedProtocol.put("maximumNewActualCalls", 7); reject(changedProtocol, onJob, on);
        changedProtocol = protocol.deepCopy(); ((ObjectNode) changedProtocol.path("writerThinkingProfile")).put("protocolRepairsAllowed", true); reject(changedProtocol, onJob, on);
        changedProtocol = protocol.deepCopy(); ((ObjectNode) changedProtocol.path("writerThinkingProfile").path("writerSources").path(0)).put("sha256", "0".repeat(64)); reject(changedProtocol, onJob, on);
        ObjectNode legacy = protocol.deepCopy(); legacy.remove(java.util.List.of("experimentProfile", "writerThinkingProfile"));
        ((ObjectNode) legacy.path("parameters")).put("executionMaxTokens", 8192);
        NaturalUsefulDeliveryEvaluationServer.validateModelParameters(legacy, job, original); passed++;
        reject(legacy, onJob, on);
        changed = original.deepCopy(); changed.put("reasoning_effort", "high"); reject(legacy, job, changed);
        reject(legacy, job, off);
        ObjectNode task = JSON.createObjectNode(); task.put("status", "FROZEN").put("selectedMaximumActualCalls", 500).put("previousActualCalls", 0);
        JSON.writeValue(evidence.getParent().resolve("task-protocol.json").toFile(), task);
        JSON.writeValue(evidence.resolve("protocol.json").toFile(), protocol);
        Path reservation = NaturalUsefulDeliveryEvaluationServer.reserve(job, protocol);
        try { NaturalUsefulDeliveryEvaluationServer.reserve(onJob, protocol); throw new AssertionError("Unresolved reservation allowed another writer call"); }
        catch (java.io.IOException expected) { passed++; }
        JSON.writeValue(reservation.toFile(), Map.of("phase", "EXECUTION", "jobId", "T01_OFF"));
        try { NaturalUsefulDeliveryEvaluationServer.reserve(job, protocol); throw new AssertionError("Writer retry allowed"); }
        catch (java.io.IOException expected) { passed++; }
        Files.writeString(evidence.resolve("closure.json"), "{\"status\":\"CLOSED\"}");
        try { NaturalUsefulDeliveryEvaluationServer.reserve(onJob, protocol); throw new AssertionError("Closed stage reopened"); }
        catch (java.io.IOException expected) { passed++; }
        System.out.println(JSON.writeValueAsString(Map.of("passed", passed, "failed", 0, "modelCalls", 0, "fixtureDirectory", fixture.toString())));
    }

    /** A rejected request must fail before transport reservation; assertions are not swallowed. */
    private static void reject(JsonNode protocol, JsonNode job, JsonNode payload) throws Exception {
        try { NaturalUsefulDeliveryEvaluationServer.validateModelParameters(protocol, job, payload); throw new AssertionError("Invalid profile was accepted"); }
        catch (java.io.IOException expected) { passed++; }
    }
}
