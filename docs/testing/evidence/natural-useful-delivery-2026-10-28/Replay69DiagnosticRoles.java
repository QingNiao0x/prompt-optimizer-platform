import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import java.nio.file.*;
import java.util.*;

/** 离线重放真实68首稿及记录；只验证69诊断修复，不伪造HTTP成功、历史记录或新模型调用。 */
public final class Replay69DiagnosticRoles {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var fixture = json.readTree(Path.of(args[0]).toFile());
        var input = json.treeToValue(fixture.path("input"), NaturalPromptInput.class).withStrategyVersion("69");
        ObjectNode wire = fixture.path("record").deepCopy();
        String firstBody = fixture.path("body").path("optimizedPrompt").asText(); wire.put("optimizedPrompt", firstBody);
        var original = json.treeToValue(wire, NaturalPromptPhasePayloads.Enhancement.class)
                .toDraft(input, new ProviderMetadata("deepseek", "deepseek:deepseek-flash", false));
        var normalized = NaturalPromptDiagnosticRoles.normalize(original, input);
        NaturalPromptRequirementsOnlyPreparation.validateUserGoals(normalized.taskUnderstanding());
        var service = new com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService(null, null, null, null, null, null);
        var validate = service.getClass().getDeclaredMethod("validateBody", NaturalPromptDraft.class, List.class, String.class);
        validate.setAccessible(true);
        String assembled = (String) validate.invoke(service, normalized, input.mandatoryRules(), input.strategyVersion());
        normalized.taskUnderstanding().review().validateEnhancement(normalized.taskUnderstanding(), firstBody);
        normalized.taskUnderstanding().review().validateEnhancement(normalized.taskUnderstanding(), assembled);
        if (!firstBody.equals(normalized.optimizedPrompt()) || !firstBody.equals(assembled)) throw new AssertionError("Body changed");
        ObjectNode result = json.createObjectNode();
        result.put("kind", "OFFLINE_CANONICAL_REPLAY_OF_ACTUAL_RESPONSE").put("fixtureOnly", true).put("actualModelCalls", 0)
                .put("originalApiStatus", 502).put("fullProductionValidationPassed", true).put("bodyUnchanged", true)
                .put("originalFailureReclassifiedAsPass", false).put("real69HttpRequestVerified", false);
        result.set("provenance", fixture.path("provenance")); result.set("validatedDraft", json.valueToTree(normalized));
        Files.writeString(Path.of(args[1]), json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println(json.writeValueAsString(Map.of("offlineOnly", true, "originalApiStatus", 502,
                "fullProductionValidationPassed", true, "bodyChanged", false, "actualCalls", 0)));
    }
}
