import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

/** Offline only: actual preparation lineage, fixed body, untouched input and model parameters for64/65. */
public final class Prepared64GuardSelfTest {
    /** 校验工具自身的有限阶段边界；夹具不发HTTP、不计入真实调用总账。 */
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules(); Path repo = Path.of(args[0]);
        Path privateRoot = Files.createTempDirectory(repo.resolve("tmp"), "prepared64-offline-");
        System.setProperty("natural.eval.root", privateRoot.toString());
        System.setProperty("natural.eval.evidence", privateRoot.toString());
        String version = args.length > 1 ? args[1] : "64";
        if (!Set.of("64", "65", "66", "67", "68", "69", "70").contains(version)) throw new IllegalArgumentException("Unknown version");
        Body59GuardSelfTest.version = version;
        var trace = json.readTree(repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28/registry63-holdout-gen/traces/007.json").toFile());
        ObjectNode request = trace.path("request").deepCopy();
        JsonNode protocol = json.valueToTree(Map.of("modelId", "deepseek:deepseek-flash"));
        Body59GuardSelfTest.effectiveAnswerChecks(repo, request, protocol, false);
        Body59GuardSelfTest.effectiveAnswerChecks(repo, request, protocol, true);
        JsonNode job = json.valueToTree(Map.of("version", version, "phase", "GENERATION"));
        JsonNode model = json.valueToTree(Map.of("upstreamModel", "deepseek-flash", "parameters", Map.of("temperature", .2),
                "naturalInferenceProfile", Map.of("name", "BODY_FIRST_REASONING_V1", "version", version, "generationMaxTokens", 32768,
                        "thinking", "enabled", "reasoningEffort", "high", "readTimeoutSeconds", 180,
                        "runnerTimeoutMs", 1140000, "planMaxTokens", 8192, "planThinking", "disabled")));
        NaturalUsefulDeliveryEvaluationServer.validateModelParameters(model, job, request);
        ObjectNode wrong = request.deepCopy(); wrong.put("max_tokens", 8192); boolean rejected = false;
        try { NaturalUsefulDeliveryEvaluationServer.validateModelParameters(model, job, wrong); }
        catch (java.io.IOException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Changed64 parameters accepted");
        System.out.println(json.writeValueAsString(Map.of("fixtureOnly", true, "actualModelCalls", 0,
                "checks", Body59GuardSelfTest.passed + 2, "ordinaryAndAnsweredInputs", true)));
    }
}
