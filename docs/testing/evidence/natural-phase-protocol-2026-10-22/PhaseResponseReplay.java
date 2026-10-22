import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.openai.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * 冻结原件的离线协议定位：直接调用本批次运行类的解析及覆盖检查，不启动服务、不调用模型。
 * 投影实验只在内存副本中移除本次发现的额外字段，用于隔离拒绝原因；不代表产品允许这些字段或质量通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class PhaseResponseReplay {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Path TRACES = Path.of("tmp/natural-phase-protocol-20261022/traces");

    /** 仅回放四个发生有界协议修复的作业及其第二次响应，原始请求和响应文件保持不变。 */
    public static void main(String[] args) throws Exception {
        // 解析方法不访问HTTP；空客户端使离线脚本没有意外发起传输的能力。
        var provider = new OpenAiCompatibleNaturalPromptProvider(null, JSON,
                new OpenAiCompatibleProperties(), (PlatformModelCatalog) null);
        var readResponse = OpenAiCompatibleNaturalPromptProvider.class.getDeclaredMethod("readResponse", String.class);
        readResponse.setAccessible(true);
        var field = OpenAiCompatibleNaturalPromptProvider.class.getDeclaredField("enhancementPhaseReader");
        field.setAccessible(true);
        ObjectReader phaseReader = (ObjectReader) field.get(provider);
        var results = new ArrayList<Map<String, Object>>();
        for (String number : List.of("017", "018", "022", "023", "030", "031", "034", "035")) {
            Path path = TRACES.resolve(number + ".json");
            byte[] original = Files.readAllBytes(path);
            JsonNode trace = JSON.readTree(original);
            String user = trace.path("request").path("messages").get(1).path("content").asText();
            ObjectNode request = (ObjectNode) JSON.readTree(user.substring(user.indexOf('{')));
            // taskSources是服务端派生的只读目录；恢复原始输入后核对目录相等，不反序列化到不可变getter列表。
            JsonNode recordedSources = request.remove("taskSources");
            NaturalPromptInput input = JSON.treeToValue(request, NaturalPromptInput.class);
            if (!JSON.valueToTree(input.taskSources()).equals(recordedSources)) throw new IllegalStateException("来源目录不一致");
            String response = trace.path("response").path("choices").get(0).path("message").path("content").asText();
            JsonNode body = (JsonNode) readResponse.invoke(provider, response);
            var result = new LinkedHashMap<String, Object>();
            result.put("trace", path.toString().replace('\\', '/'));
            result.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
            result.put("jobId", trace.path("jobId").asText());
            result.put("strategyVersion", input.strategyVersion());
            result.put("rootClosingBraceRecovered", !isStandaloneJson(response));
            result.put("frozenValidation", validate(body, input, phaseReader));
            result.put("optimizedPrompt", body.path("optimizedPrompt").asText());
            if (Set.of("022", "030", "031").contains(number)) {
                String unexpected = "022".equals(number) ? "task" : "kind";
                ObjectNode projected = body.deepCopy();
                JsonNode removed = ((ObjectNode) projected.get("task")).remove(unexpected);
                result.put("diagnosticProjection", Map.of("removedPath", "task." + unexpected,
                        "removedType", removed.getNodeType().name(), "removedValue", removed,
                        "validation", validate(projected, input, phaseReader),
                        "scope", "仅隔离额外字段的影响；未改产品、原件或正式交付状态"));
            }
            result.put("independentChecks", inspect(body, input));
            results.add(result);
            if (!Arrays.equals(original, Files.readAllBytes(path))) throw new IllegalStateException("原件发生变化");
        }
        Path output = Path.of("docs/testing/evidence/natural-phase-protocol-2026-10-22/replay-result.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "scope", "离线回放8份原件；零模型调用；冻结产品与原件不变；通过协议不代表语义或实际作品合格",
                "results", results));
        System.out.println("offlineReplayResponses=" + results.size());
    }

    /** 与运行期读取相同的阶段ObjectReader及领域校验；保留原异常类型和字段路径供归因。 */
    private static Map<String, Object> validate(JsonNode body, NaturalPromptInput input, ObjectReader phaseReader) {
        try {
            if (NaturalPromptStageInstructions.supports(input.strategyVersion())) {
                NaturalPromptPhasePayloads.Enhancement payload = phaseReader.readValue(body);
                payload.toDraft(input, new ProviderMetadata("deepseek", "deepseek-flash", false, ""));
            } else {
                NaturalPromptDraft.Framework.valueOf(body.path("framework").asText());
                var task = JSON.treeToValue(body.get("taskUnderstanding"), NaturalTaskUnderstanding.class).resolve(input);
                task.review().validateEnhancement(task, body.path("optimizedPrompt").asText().strip());
            }
            return Map.of("valid", true);
        } catch (Exception failure) {
            var result = new LinkedHashMap<String, Object>();
            result.put("valid", false);
            result.put("exception", failure.getClass().getName());
            result.put("message", failure.getMessage());
            if (failure instanceof UnrecognizedPropertyException unknown) {
                result.put("unknownProperty", unknown.getPropertyName());
                result.put("path", unknown.getPathReference());
            }
            if (failure instanceof NaturalTaskReview.MissingSources missing) {
                result.put("sourceRepairInstruction", missing.repairInstruction());
            }
            return result;
        }
    }

    /** 独立列出来源缺失、正文引文差异及跨阶段记录，避免首个异常掩盖后续证据。 */
    private static Map<String, Object> inspect(JsonNode body, NaturalPromptInput input) {
        boolean phase = body.has("task");
        JsonNode task = body.path(phase ? "task" : "taskUnderstanding");
        String prompt = body.path("optimizedPrompt").asText();
        var referenced = new TreeSet<String>();
        var mismatches = new ArrayList<Map<String, Object>>();
        for (JsonNode requirement : task.path("requirements")) {
            for (JsonNode evidence : requirement.path("evidence")) referenced.add(evidence.path("sourceId").asText());
        }
        var required = new TreeSet<String>();
        input.taskSources().stream().filter(source -> source.kind() == NaturalTaskSource.Kind.ORIGINAL_REQUEST
                || source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER).forEach(source -> required.add(source.id()));
        required.removeAll(referenced);
        for (String group : List.of("requirements", "additions")) {
            JsonNode entries = phase ? task.path(group) : task.path("review")
                    .path("requirements".equals(group) ? "requirementCoverage" : "additionCoverage");
            for (int index = 0; index < entries.size(); index++) {
                for (JsonNode quote : entries.get(index).path(phase ? "bodyQuotes" : "quotes")) {
                    if (!prompt.contains(quote.asText())) mismatches.add(Map.of("group", group,
                            "index", index, "quote", quote.asText()));
                }
            }
        }
        return Map.of("missingRequiredSources", required, "bodyQuoteMismatches", mismatches,
                "planImpacts", task.path("review").path("planImpacts"));
    }

    /** 只记录运行期已有的单个闭合符兼容是否被使用，不在本脚本中增加修复行为。 */
    private static boolean isStandaloneJson(String value) {
        try { JSON.readTree(value); return true; }
        catch (Exception failure) { return false; }
    }
}
