import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.provider.domain.RequirementBoundaryContract;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/** 同一探针加载父／新冻结class，观测误拒及保留保护；不调用模型、不改响应或校验结论。 */
public final class BoundaryProbe {
    /** 只接受固定候选标签，不允许参数控制任意输出路径。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("candidate27-r2", "candidate28").contains(args[0])) throw new IllegalArgumentException("Frozen candidate required");
        String origin = RequirementBoundaryContract.class.getProtectionDomain().getCodeSource().getLocation().toString();
        if (!origin.contains(args[0] + "-20261017/frozen/classes")) throw new IllegalStateException("Wrong frozen class origin");
        var json = new ObjectMapper();
        var values = new ArrayList<Map<String, Object>>();
        var original338 = model(json, 338);
        var sources = List.of(
                Map.of("id", "actual336", "text", section(model(json, 336), "CONSTRAINTS"), "shouldAccept", true),
                Map.of("id", "actual338", "text", section(original338, "ACCEPTANCE"), "shouldAccept", true),
                Map.of("id", "explicitPermissionAfterBan", "text", "不得编造P值但允许使用假设数据。", "shouldAccept", false),
                Map.of("id", "explicitPermissionAfterBan2", "text", "禁止编造P值但可以使用虚构数据。", "shouldAccept", false),
                Map.of("id", "properNameIsNotContrast", "text", "允许但马县采集的假设数据。", "shouldAccept", false),
                Map.of("id", "laterBanDoesNotCancelPermission", "text", "允许使用模拟数据但禁止使用假设数据。", "shouldAccept", false));
        var boundary = RequirementBoundaryContract.from("不得使用假设数据。", List.of(), List.of());
        for (var item : sources) {
            boolean accepted = true; String reason = "";
            try { boundary.validate((String) item.get("text"), "probe"); }
            catch (ProviderResponseValidationException failure) { accepted = false; reason = failure.getReason().name(); }
            values.add(Map.of("id", item.get("id"), "text", item.get("text"), "accepted", accepted,
                    "expectedAccepted", item.get("shouldAccept"), "gatePass", accepted == (boolean) item.get("shouldAccept"), "reason", reason));
        }
        List<String> ambiguities = json.readerForListOf(String.class).readValue(original338.path("ambiguities"));
        String reason = "NOT_REJECTED";
        try { json.treeToValue(original338.path("assessment"), TaskEnhancementAssessment.class).validateModelDecisions(ambiguities); }
        catch (ProviderResponseValidationException failure) { reason = failure.getReason().name(); }
        if (!"ASSESSMENT_DECISION_CONFLICT".equals(reason)) throw new IllegalStateException("Independent protocol rejection weakened");
        var result = Map.of("candidate", args[0], "classOrigin", origin, "checks", values,
                "actual338AssessmentStillRejected", reason, "sourceResponsesUnchanged", true, "newModelCalls", 0);
        Files.writeString(Path.of("docs/testing/evidence/me00a-candidate28-offline-2026-10-17/boundary-probe-" + args[0] + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(result), java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println(args[0] + ": " + values.stream().filter(row -> (boolean) row.get("gatePass")).count() + "/" + values.size() + " independent boundary checks; protocol rejection preserved");
    }

    /** 原件仅用于提取已有字符串，不补字段、不清空真正问题。 */
    private static JsonNode model(ObjectMapper json, int number) throws Exception {
        var trace = json.readTree(Path.of("tmp/me00a-20261012/traces/" + number + ".json").toFile());
        return json.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
    }

    private static String section(JsonNode response, String type) {
        for (var section : response.path("sections")) if (type.equals(section.path("type").asText())) return section.path("content").asText();
        throw new IllegalStateException("Section missing");
    }
}
