import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** 离线测量既有真实请求的正文投影，不发HTTP，不以字符减少宣称质量或计费收益。 */
public final class MeasureWriter70Input {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        JsonNode trace = json.readTree(Path.of(args[0]).toFile());
        String message = trace.path("request").path("messages").get(1).path("content").asText();
        ObjectNode full = (ObjectNode) json.readTree(message.substring(message.indexOf('{'))); full.put("strategyVersion", "70");
        JsonNode before = full.deepCopy();
        var type = Class.forName("com.promptoptimizer.provider.infrastructure.openai.NaturalPromptWriterInput");
        var method = type.getDeclaredMethod("project", String.class, String.class, boolean.class, boolean.class, JsonNode.class);
        method.setAccessible(true);
        JsonNode body = (JsonNode) method.invoke(null, "70", "prompt.optimize", false, false, full);
        List<String> preserved = List.of("rawPrompt", "context", "actualAnswers", "conversation", "mandatoryRules", "modelId",
                "planningContext", "strategyVersion", "inputBoundary");
        for (String field : preserved) if (!full.path(field).equals(body.path(field))) throw new AssertionError("Original changed: " + field);
        if (!full.equals(before)) throw new AssertionError("Full input mutated");
        if (method.invoke(null, "70", "prompt.optimize", false, true, full) != full) throw new AssertionError("Record input projected");
        if (method.invoke(null, "70", "plan.generate", false, false, full) != full) throw new AssertionError("Plan input projected");
        int previous = json.writeValueAsString(full).length(), projected = json.writeValueAsString(body).length();
        ObjectNode result = json.createObjectNode();
        result.put("source", args[0]).put("kind", "OFFLINE_WRITER_INPUT_PROJECTION").put("actualModelCalls", 0)
                .put("beforeJsonCharacters", previous).put("afterJsonCharacters", projected).put("reducedCharacters", previous - projected)
                .put("reducedFraction", 1.0 - (double) projected / previous).put("fullInputUnchanged", true)
                .put("planAndRecordRetainFullInput", true).put("qualityBenefitProven", false).put("tokenOrBillingBenefitMeasured", false);
        result.set("preservedOriginalFields", json.valueToTree(preserved));
        Files.writeString(Path.of(args[1]), json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println(json.writeValueAsString(result));
    }
}
