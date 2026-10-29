import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/** New native model profile parameter boundaries only; no server, catalog mutation or network call. */
public final class NativeModelSelectionProfileSelfTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int passed;

    public static void main(String[] args) throws Exception {
        String repo = System.getProperty("user.dir");
        System.setProperty("natural.eval.root", java.nio.file.Path.of(repo, "tmp/native-pro50-offline-no-service").toString());
        System.setProperty("natural.eval.evidence", java.nio.file.Path.of(repo, "docs/testing/evidence/natural-useful-delivery-2026-10-28").toString());
        ObjectNode protocol = JSON.createObjectNode();
        protocol.put("modelId", "deepseek:deepseek-v4-pro").put("upstreamModel", "deepseek-v4-pro")
                .put("endpoint", "https://api.deepseek.com/chat/completions").put("maximumNewActualCalls", 12);
        protocol.set("phaseLimits", JSON.valueToTree(Map.of("GENERATION", 12, "PLAN", 0, "EXECUTION", 0, "REVIEW", 0)));
        protocol.set("parameters", JSON.valueToTree(Map.of("generationMaxTokens", 8192, "temperature", 0.2, "thinking", "disabled", "stream", false)));
        protocol.set("modelSelectionProfile", JSON.valueToTree(Map.of("name", "OFFICIAL_MODEL_SELECTION_ABLATION_V1",
                "modelId", "deepseek:deepseek-v4-pro", "upstreamModel", "deepseek-v4-pro", "routeKey", "deepseek",
                "endpoint", "https://api.deepseek.com/chat/completions", "baselineStage", "registry50-development",
                "baselineJobsSha256", "a".repeat(64), "baselineProtocolSha256", "b".repeat(64), "maximumActualCalls", 12)));
        ObjectNode job = JSON.createObjectNode().put("id", "Q01_V50_PRO_GEN").put("caseId", "Q01").put("phase", "GENERATION").put("version", "50");
        ObjectNode payload = JSON.valueToTree(Map.of("model", "deepseek-v4-pro", "temperature", 0.2, "max_tokens", 8192,
                "thinking", Map.of("type", "disabled"), "stream", false, "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", "Synthetic parameter guard only"))));
        NaturalUsefulDeliveryEvaluationServer.validateModelParameters(protocol, job, payload); passed++;
        ObjectNode changed = job.deepCopy(); changed.put("version", "51"); reject(protocol, changed, payload);
        changed = protocol.deepCopy(); changed.put("endpoint", "https://unapproved.invalid/chat/completions"); reject(changed, job, payload);
        changed = protocol.deepCopy(); changed.put("modelId", "deepseek:deepseek-flash"); reject(changed, job, payload);
        changed = payload.deepCopy(); changed.put("model", "deepseek-flash"); reject(protocol, job, changed);
        changed = payload.deepCopy(); changed.put("max_tokens", 32768); reject(protocol, job, changed);
        changed = payload.deepCopy(); changed.set("thinking", JSON.valueToTree(Map.of("type", "enabled"))); reject(protocol, job, changed);
        changed = payload.deepCopy(); changed.put("reasoning_effort", "high"); reject(protocol, job, changed);
        changed = payload.deepCopy(); changed.put("top_p", 0.8); reject(protocol, job, changed);
        changed = payload.deepCopy(); changed.set("response_format", JSON.valueToTree(Map.of("type", "text"))); reject(protocol, job, changed);
        changed = protocol.deepCopy(); ((ObjectNode) changed.path("parameters")).put("temperature", 0.8);
        ObjectNode changedPayload = payload.deepCopy().put("temperature", 0.8); reject(changed, job, changedPayload);
        changed = protocol.deepCopy(); ((ObjectNode) changed.path("modelSelectionProfile")).put("apiKey", "synthetic-unapproved-parameter"); reject(changed, job, payload);
        ObjectNode legacy = protocol.deepCopy(); legacy.remove("modelSelectionProfile"); legacy.put("modelId", "deepseek:deepseek-flash").put("upstreamModel", "deepseek-flash");
        ObjectNode flash = payload.deepCopy().put("model", "deepseek-flash");
        NaturalUsefulDeliveryEvaluationServer.validateModelParameters(legacy, job, flash); passed++;
        System.out.println(JSON.writeValueAsString(Map.of("passed", passed, "failed", 0, "actualModelCalls", 0,
                "scope", "Only finite Pro50 parameter overrides and one legacy Flash compatibility smoke")));
    }

    private static void reject(JsonNode protocol, JsonNode job, JsonNode payload) throws Exception {
        try { NaturalUsefulDeliveryEvaluationServer.validateModelParameters(protocol, job, payload); throw new AssertionError("Unexpected parameter override accepted"); }
        catch (java.io.IOException expected) { passed++; }
    }
}
