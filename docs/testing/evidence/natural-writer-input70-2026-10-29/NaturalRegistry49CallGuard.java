import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleNaturalPromptProvider;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

/** Finite49/50/51/52 transport checks; malformed preparation responses still reach the product repair path. */
public final class NaturalRegistry49CallGuard {
    private static final Set<String> REGISTRY_VERSIONS = Set.of("49", "50", "51", "52");
    private static final String REPAIR_PREFIX = "\n上次响应未满足协议或必要保护。请重新按完整输入生成合法JSON并保留全部要求；每个字段只出现一次，不添加额外内容。";
    private static final Set<String> INPUT_FIELDS = Set.of("rawPrompt", "context", "actualAnswers", "conversation",
            "mandatoryRules", "modelId", "planningContext", "strategyVersion", "taskSources", "inputBoundary",
            "taskScope", "preparedRequirements");

    /** A trace link is evidence of the actual call sequence, not a semantic quality or authorization judgment. */
    public record Audit(String callStage, String preparedTrace, String originalInputSha256,
            String preparedRequirementsSha256, int preparedRequirementCount) { }

    /** 54独立单正文入口：完整来源和真实题答保留，禁止混入旧registry/scope或增加第三次调用。 */
    public static Audit verify54(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifySinglePass(json, runtime, protocol, job, input, request, "54");
    }

    /** 55使用相同完整输入与来源检查，版本由实际任务决定，不更改54原响应或请求。 */
    public static Audit verify55(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifySinglePass(json, runtime, protocol, job, input, request, "55");
    }

    /** 56只改变内部维度指导；完整输入、来源、题答及最多两次的保护与55相同。 */
    public static Audit verify56(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifySinglePass(json, runtime, protocol, job, input, request, "56");
    }

    /** 57的元数据请求必须从本job真实首稿按产品算法派生，不能更换首稿或夹带正文重写。 */
    public static Audit verify57(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyMetadataCandidate(json, runtime, protocol, job, input, request, "57");
    }

    /** 58仅增加服务端引用角色说明，首稿和元数据补丁仍需分别验证完整原输入。 */
    public static Audit verify58(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyMetadataCandidate(json, runtime, protocol, job, input, request, "58");
    }

    /** 60沿58传输和输入边界，只接受职责澄清后的冻结system，不增加写作/审阅轮次。 */
    public static Audit verify60(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyMetadataCandidate(json, runtime, protocol, job, input, request, "60");
    }

    /** 59正文先行：只能登记实际首稿，来源核对及其补丁均不得改写首稿或更换完整原输入。 */
    public static Audit verify59(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "59");
    }

    /** 61只更换正文委托，记录阶段与不可更改的实际首稿谱系沿59。 */
    public static Audit verify61(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "61");
    }

    /** 62只为真实题答增加一次既有有效要求准备；后续引用须来自本job实际成功的准备响应。 */
    public static Audit verify62(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "62");
    }

    /** 63引用目录与补正指导必须由当前产品算法从当前实际首稿确定，不能借旧目录扩大来源。 */
    public static Audit verify63(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "63");
    }

    /** 64所有请求先用真实准备响应；后续正文和记录不得绕过或替换这份已核验要求。 */
    public static Audit verify64(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "64");
    }

    /** 65准备职责同时解释原始表达自身的有效状态；其余完整输入、固定正文和修复边界不变。 */
    public static Audit verify65(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "65");
    }

    /** 66仅正文system包含内部示例，其余65准备谱系和首稿约束逐字保留。 */
    public static Audit verify66(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "66");
    }

    /** 67的补全建议必须来自同次真实准备，登记阶段不能继承其建议作为事实。 */
    public static Audit verify67(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "67");
    }

    /** 修正合法枚举说明后，其余同次准备谱系及固定首稿校验与67相同。 */
    public static Audit verify68(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "68");
    }

    /** 69仅归一来源角色诊断，真实准备谱系、固定正文与原始输入边界仍完全检查。 */
    public static Audit verify69(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "69");
    }

    /** 70对照真实准备推导出的正文投影；不是放宽原始信息或来源检查。 */
    public static Audit verify70(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request)
            throws IOException {
        return verifyBodyFirst(json, runtime, protocol, job, input, request, "70");
    }

    private static Audit verifyBodyFirst(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input,
            JsonNode request, String version) throws IOException {
        try {
            if (!job.path("version").asText().equals(version) || !job.path("phase").asText().equals("GENERATION")
                    || !input.path("strategyVersion").asText().equals(version)) throw boundary("BODY_FIRST_STAGE");
            if (request.path("messages").size() != 2 || !request.path("messages").path(0).path("role").asText().equals("system")
                    || !request.path("messages").path(1).path("role").asText().equals("user")) throw boundary("BODY_FIRST_MESSAGES");
            boolean effective = Set.of("64", "65", "66", "67", "68", "69", "70").contains(version) || Set.of("62", "63").contains(version) && input.path("actualAnswers").size() > 0;
            ObjectNode core = input.deepCopy(); core.remove(List.of("protocolRepair", "fidelityDraft"));
            if (Set.of("67", "68", "69", "70").contains(version)) core.remove("completionSuggestions");
            if (effective) core.remove(List.of("taskScope", "preparedRequirements"));
            NaturalPromptInput original = naturalInput(json, core);
            boolean projectedWriter = version.equals("70") && input.has("preparedRequirements") && !input.has("fidelityDraft");
            if (projectedWriter) {
                if (input.has("taskSources")) throw boundary("BODY_FIRST_WRITER_CATALOG_NOT_PROJECTED");
                // 只恢复审计用编号目录以比较完整原始信息；实际发送树不改变，catalog由原输入确定。
                core.set("taskSources", json.valueToTree(original.taskSources()));
            }
            if (!original.rawPrompt().equals(job.path("approvedEnhancementInput").path("rawPrompt").asText())) throw boundary("BODY_FIRST_FROZEN_RAW_DRIFT");
            ObjectNode expected = json.valueToTree(original);
            expected.putObject("inputBoundary").put("stage", "PROMPT_OPTIMIZATION")
                    .put("contextMeaning", "本次增强可见的材料，不是下游执行现场的完整资料或工具清单")
                    .set("referenceRoles", json.valueToTree(com.promptoptimizer.provider.domain.NaturalPromptSinglePassInstructions.REFERENCE_ROLES));
            if (!expected.equals(core) || !original.modelId().equals(protocol.path("modelId").asText())) throw boundary("BODY_FIRST_ORIGINAL_DRIFT");
            List<JsonNode> bodies = new ArrayList<>(), records = new ArrayList<>(), preparations = new ArrayList<>();
            try (var paths = Files.list(runtime.resolve("traces"))) {
                for (Path path : paths.filter(item -> item.toString().endsWith(".json")).sorted().toList()) {
                    var trace = json.readTree(path.toFile());
                    if (!trace.path("jobId").asText().equals(job.path("id").asText())) continue;
                    ObjectNode past = requestInput(json, trace.path("request")).deepCopy();
                    boolean record = past.has("fidelityDraft"), prepared = past.has("preparedRequirements");
                    past.remove(List.of("protocolRepair", "fidelityDraft"));
                    if (Set.of("67", "68", "69", "70").contains(version)) past.remove("completionSuggestions");
                    if (effective) past.remove(List.of("taskScope", "preparedRequirements"));
                    if (version.equals("70") && prepared && !record) {
                        if (past.has("taskSources")) throw boundary("BODY_FIRST_PAST_CATALOG_NOT_PROJECTED");
                        past.set("taskSources", json.valueToTree(original.taskSources()));
                    }
                    if (!past.equals(core)) throw boundary("BODY_FIRST_PAST_INPUT_DRIFT");
                    (record ? records : effective && !prepared ? preparations : bodies).add(trace);
                }
            }
            boolean record = input.has("fidelityDraft"); String system = request.path("messages").path(0).path("content").asText();
            if (effective && !input.has("preparedRequirements")) {
                if (record || input.has("taskScope") || input.has("protocolRepair") || input.has("completionSuggestions") || !bodies.isEmpty() || !records.isEmpty()
                        || preparations.size() > 1) throw boundary("BODY_FIRST_PREPARATION_SEQUENCE");
                if (!preparations.isEmpty()) {
                    boolean validPreparation = false;
                    try {
                        normalizeActualPreparation(json, original, responseContent(json, preparations.getFirst()).toString());
                        validPreparation = true;
                    } catch (ReflectiveOperationException | IOException | RuntimeException invalidPreparation) {
                        // Only an actually invalid response may use the product's one protocol repair.
                    }
                    if (validPreparation) throw boundary("BODY_FIRST_PREPARATION_QUALITY_RETRY");
                }
                String instruction = Set.of("67", "68", "69", "70").contains(version) ? com.promptoptimizer.provider.domain.NaturalPromptCompletionPreparationInstructions.preparationInstruction(version)
                        : Set.of("65", "66").contains(version) ? com.promptoptimizer.provider.domain.NaturalPromptBodyFirstInstructions.CURRENT_REQUIREMENTS_PREPARE
                        : input.path("actualAnswers").isEmpty()
                        ? com.promptoptimizer.provider.domain.NaturalPromptEffectiveRegistryInstructions.PREPARE
                        : com.promptoptimizer.provider.domain.NaturalPromptEffectiveRegistryInstructions.PREPARE_WITH_ANSWERS;
                if (preparations.isEmpty() ? !system.equals(instruction) : !system.startsWith(instruction + REPAIR_PREFIX))
                    throw boundary("BODY_FIRST_PREPARATION_SYSTEM");
                return new Audit("REQUIREMENT_PREPARATION", null, sha(json.writeValueAsBytes(core)), null, 0);
            }
            if (effective) {
                if (preparations.isEmpty() || preparations.size() > 2 || !input.path("taskScope").isObject()) throw boundary("BODY_FIRST_PREPARATION_MISSING");
                Object prepared = normalizeActualPreparation(json, original, responseContent(json, preparations.getLast()).toString());
                if (projectedWriter) {
                    Object completion = normalizePreparationPayload(json, original, responseContent(json, preparations.getLast()).toString());
                    ObjectNode full = core.deepCopy();
                    full.set("preparedRequirements", json.valueToTree(prepared.getClass().getMethod("requirements").invoke(prepared)));
                    full.set("taskScope", json.valueToTree(prepared.getClass().getMethod("scope").invoke(prepared)));
                    full.set("completionSuggestions", json.valueToTree(completion.getClass().getMethod("suggestions").invoke(completion)));
                    if (!input.equals(projectWriter70(full))) throw boundary("BODY_FIRST_PROJECTED_INPUT_DRIFT");
                } else if (!input.path("preparedRequirements").equals(json.valueToTree(prepared.getClass().getMethod("requirements").invoke(prepared)))
                        || !input.path("taskScope").equals(json.valueToTree(prepared.getClass().getMethod("scope").invoke(prepared))))
                    throw boundary("BODY_FIRST_PREPARED_DRIFT");
            }
            if (Set.of("67", "68", "69", "70").contains(version)) {
                if (record && input.has("completionSuggestions")) throw boundary("BODY_FIRST_RECORD_SUGGESTIONS");
                if (!record && !projectedWriter) {
                    Object completion = normalizePreparationPayload(json, original, responseContent(json, preparations.getLast()).toString());
                    if (!input.path("completionSuggestions").equals(json.valueToTree(completion.getClass().getMethod("suggestions").invoke(completion))))
                        throw boundary("BODY_FIRST_COMPLETION_DRIFT");
                }
            }
            if (!record) {
                if (input.has("protocolRepair") || !records.isEmpty() || bodies.size() > 1) throw boundary("BODY_FIRST_WRITER_SEQUENCE");
                if (!bodies.isEmpty() && safeFirstBody(json, bodies.getFirst()) != null) throw boundary("BODY_FIRST_QUALITY_RETRY");
                String instruction = com.promptoptimizer.provider.domain.NaturalPromptBodyFirstInstructions.bodyInstruction(version, effective);
                if (bodies.isEmpty() ? !system.equals(instruction) : !system.startsWith(instruction + REPAIR_PREFIX)) throw boundary("BODY_FIRST_SYSTEM");
                return new Audit("BODY_GENERATION", null, sha(json.writeValueAsBytes(core)), null, 0);
            }
            if (bodies.isEmpty() || bodies.size() > 2 || records.size() > 1) throw boundary("BODY_FIRST_RECORD_SEQUENCE");
            String firstBody = safeFirstBody(json, bodies.getLast());
            if (firstBody == null || !input.path("fidelityDraft").equals(json.valueToTree(Map.of("optimizedPrompt", firstBody)))) throw boundary("BODY_FIRST_BODY_DRIFT");
            if (input.has("protocolRepair")) {
                if (records.size() != 1) throw boundary("BODY_FIRST_PATCH_SEQUENCE");
                ObjectNode auditInput = input.deepCopy(); auditInput.remove("protocolRepair");
                if (!auditInput.equals(requestInput(json, records.getFirst().path("request")))) throw boundary("BODY_FIRST_RECORD_INPUT_DRIFT");
                JsonNode response = responseContent(json, records.getFirst());
                if (!(response instanceof ObjectNode object) || response.has("optimizedPrompt")) throw boundary("BODY_FIRST_RECORD_FORMAT");
                ObjectNode bound = object.deepCopy().put("optimizedPrompt", firstBody);
                Class<?> type = Class.forName("com.promptoptimizer.provider.infrastructure.openai.NaturalPromptMetadataRepair");
                var inspect = type.getDeclaredMethod("inspect", JsonNode.class, JsonNode.class, NaturalPromptInput.class); inspect.setAccessible(true);
                var repair = ((Optional<?>) inspect.invoke(null, auditInput, bound, original)).orElseThrow();
                var user = repair.getClass().getDeclaredMethod("userMessage", ObjectMapper.class); user.setAccessible(true);
                String message = (String) user.invoke(repair, json);
                if (!json.readTree(message.substring(message.indexOf('{'))).equals(input)) throw boundary("BODY_FIRST_PATCH_DATA");
                var instruction = repair.getClass().getDeclaredMethod("instruction"); instruction.setAccessible(true);
                if (!system.equals(instruction.invoke(repair))) throw boundary("BODY_FIRST_PATCH_SYSTEM");
                return new Audit("METADATA_REPAIR", job.path("id").asText(), sha(json.writeValueAsBytes(core)), null, 0);
            }
            String instruction = com.promptoptimizer.provider.domain.NaturalPromptBodyFirstInstructions.RECORD;
            if (records.isEmpty() ? !system.equals(instruction) : !system.startsWith(instruction + REPAIR_PREFIX)) throw boundary("BODY_FIRST_RECORD_SYSTEM");
            return new Audit("RECORD_LINKING", job.path("id").asText(), sha(json.writeValueAsBytes(core)), null, 0);
        } catch (ReflectiveOperationException | RuntimeException failure) { throw boundary("BODY_FIRST59_INPUT_FAILED"); }
    }

    /** 沿实际Provider的JSON恢复边界读取首次正文，不能从失败、截断或含凭据的响应借用正文。 */
    private static String safeFirstBody(ObjectMapper json, JsonNode trace) {
        try {
            JsonNode body = responseContent(json, trace);
            if (!body.isObject() || body.size() != 1 || !body.path("optimizedPrompt").isTextual()) return null;
            String text = body.path("optimizedPrompt").asText().strip();
            return text.isBlank() || text.length() > 200_000 || new com.promptoptimizer.common.security.SensitiveValueScanner().containsCredential(text) ? null : text;
        } catch (ReflectiveOperationException | IOException | RuntimeException failure) { return null; }
    }

    private static JsonNode responseContent(ObjectMapper json, JsonNode trace) throws ReflectiveOperationException, IOException {
        if (trace.path("httpStatus").asInt() != 200 || !trace.path("response").path("choices").path(0).path("finish_reason").asText().equals("stop"))
            throw boundary("BODY_FIRST_RESPONSE_INCOMPLETE");
        var provider = new com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleNaturalPromptProvider(null, json,
                new com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties(), null);
        var unwrap = provider.getClass().getDeclaredMethod("unwrap", String.class); unwrap.setAccessible(true);
        var parse = provider.getClass().getDeclaredMethod("readResponse", String.class); parse.setAccessible(true);
        return (JsonNode) parse.invoke(provider, unwrap.invoke(provider, trace.path("response").path("choices").path(0).path("message").path("content").asText()));
    }

    private static Audit verifyMetadataCandidate(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job,
            JsonNode input, JsonNode request, String version) throws IOException {
        if (!input.has("protocolRepair")) return verifySinglePass(json, runtime, protocol, job, input, request, version);
        try {
            if (!job.path("version").asText().equals(version) || !job.path("phase").asText().equals("GENERATION")
                    || !input.path("strategyVersion").asText().equals(version)) throw boundary("WRONG_STAGE");
            if (!request.path("messages").isArray() || request.path("messages").size() != 2
                    || !request.path("messages").path(0).path("role").asText().equals("system")
                    || !request.path("messages").path(1).path("role").asText().equals("user")) throw boundary("MISSING_ACTUAL_REQUEST");
            List<JsonNode> attempts = new ArrayList<>();
            try (var paths = Files.list(runtime.resolve("traces"))) {
                for (Path path : paths.filter(item -> item.toString().endsWith(".json")).toList()) {
                    var trace = json.readTree(path.toFile());
                    if (trace.path("jobId").asText().equals(job.path("id").asText())) attempts.add(trace);
                }
            }
            if (attempts.size() != 1) throw boundary("METADATA_REPAIR_SEQUENCE");
            JsonNode first = attempts.get(0);
            if (first.path("httpStatus").asInt() != 200 || !first.path("response").path("choices").path(0).path("finish_reason").asText().equals("stop"))
                throw boundary("METADATA_FIRST_RESPONSE_MISSING");
            ObjectNode core = input.deepCopy(); core.remove("protocolRepair");
            if (!requestInput(json, first.path("request")).equals(core)) throw boundary("ORIGINAL_INPUT_DRIFT");
            NaturalPromptInput original = naturalInput(json, core);
            JsonNode candidate = json.readTree(first.path("response").path("choices").path(0).path("message").path("content").asText());
            Class<?> type = Class.forName("com.promptoptimizer.provider.infrastructure.openai.NaturalPromptMetadataRepair");
            var inspect = type.getDeclaredMethod("inspect", JsonNode.class, JsonNode.class, NaturalPromptInput.class); inspect.setAccessible(true);
            var repair = ((Optional<?>) inspect.invoke(null, core, candidate, original)).orElseThrow();
            var user = repair.getClass().getDeclaredMethod("userMessage", ObjectMapper.class); user.setAccessible(true);
            String expected = (String) user.invoke(repair, json);
            String actual = request.path("messages").path(1).path("content").asText();
            if (!json.readTree(expected.substring(expected.indexOf('{'))).equals(json.readTree(actual.substring(actual.indexOf('{')))))
                throw boundary("METADATA_REPAIR_DATA_DRIFT");
            var instruction = type.getDeclaredField("INSTRUCTION"); instruction.setAccessible(true);
            if (!request.path("messages").path(0).path("content").asText().equals(instruction.get(null)))
                throw boundary("WRONG_SYSTEM_VARIANT");
            return new Audit("METADATA_REPAIR", first.path("jobId").asText(), sha(json.writeValueAsBytes(core)), null, 0);
        } catch (ReflectiveOperationException | RuntimeException failure) { throw boundary("METADATA_REPAIR_DERIVATION_FAILED"); }
    }

    private static Audit verifySinglePass(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job,
            JsonNode input, JsonNode request, String version) throws IOException {
        try {
            if (!job.path("version").asText().equals(version) || !job.path("phase").asText().equals("GENERATION")
                    || !input.path("strategyVersion").asText().equals(version)) throw boundary("WRONG_STAGE");
            if (!request.path("messages").isArray() || request.path("messages").size() != 2
                    || !request.path("messages").path(0).path("role").asText().equals("system")
                    || !request.path("messages").path(1).path("role").asText().equals("user")) throw boundary("MISSING_ACTUAL_REQUEST");
            for (var names = input.fieldNames(); names.hasNext();) {
                String name = names.next();
                if (!INPUT_FIELDS.contains(name) || name.equals("taskScope") || name.equals("preparedRequirements"))
                    throw boundary("UNKNOWN_INPUT_FIELD");
            }
            ObjectNode core = originalInput(input);
            NaturalPromptInput original = naturalInput(json, core);
            if (!original.modelId().equals(protocol.path("modelId").asText())
                    || !json.valueToTree(original.taskSources()).equals(core.path("taskSources")))
                throw boundary("SOURCE_OR_MODEL_CATALOG_DRIFT");
            ObjectNode expectedBoundary = json.valueToTree(Map.of("stage", "PROMPT_OPTIMIZATION",
                    "contextMeaning", "本次增强可见的材料，不是下游执行现场的完整资料或工具清单"));
            if (Set.of("58", "60").contains(version)) expectedBoundary.set("referenceRoles", json.valueToTree(
                    Class.forName("com.promptoptimizer.provider.domain.NaturalPromptSinglePassInstructions").getField("REFERENCE_ROLES").get(null)));
            if (!core.path("inputBoundary").equals(expectedBoundary)) throw boundary("INPUT_BOUNDARY_DRIFT");
            int attempts = 0;
            try (var paths = Files.list(runtime.resolve("traces"))) {
                for (Path path : paths.filter(item -> item.toString().endsWith(".json")).toList()) {
                    JsonNode trace = json.readTree(path.toFile());
                    if (!trace.path("jobId").asText().equals(job.path("id").asText())) continue;
                    if (!requestInput(json, trace.path("request")).equals(core)) throw boundary("ORIGINAL_INPUT_DRIFT");
                    attempts++;
                }
            }
            String expected = (String) Class.forName("com.promptoptimizer.provider.domain.NaturalPromptSinglePassInstructions")
                    .getField(version.equals("60") ? "RESPONSIBILITY_ENHANCE" : Set.of("56", "57", "58").contains(version) ? "GOAL_ROLE_ENHANCE" : "ENHANCE").get(null);
            String actual = request.path("messages").path(0).path("content").asText();
            if (attempts == 0 ? !actual.equals(expected) : attempts != 1 || !actual.startsWith(expected + REPAIR_PREFIX))
                throw boundary("WRONG_SYSTEM_VARIANT");
            return new Audit("BODY_GENERATION", null, sha(json.writeValueAsBytes(core)), null, 0);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw boundary("SINGLE" + version + "_PRODUCT_INPUT_FAILED");
        }
    }

    /**
     * Before sending a finite registry request, compare original input and resolve the actual prior preparation
     * with the product's strict reader. No registry is synthesized and no response is rewritten or retried here.
     */
    public static Audit verify(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input) throws IOException {
        return verify(json, runtime, protocol, job, input, null);
    }

    /** The52-only wire check is additive; old finite versions retain their exact input/registry checks. */
    public static Audit verify(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request) throws IOException {
        try {
            if (job.path("version").asText().equals("52")) verify52System(json, runtime, job, input, request);
            return inspect(json, runtime, protocol, job, input);
        }
        catch (IOException failure) {
            String code = failure.getMessage() != null && failure.getMessage().matches("GUARD49_[A-Z_]+")
                    ? failure.getMessage() : "GUARD49_BOUNDARY_CHECK_FAILED";
            recordRejection(json, runtime, job, code);
            throw new IOException(code);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            String code = "GUARD49_PRODUCT_PREPARATION_NORMALIZATION_FAILED";
            recordRejection(json, runtime, job, code);
            // Do not attach reflection/Jackson messages that might contain original input fragments.
            throw new IOException(code);
        }
    }

    /**
     * Select the real frozen52 constant from complete actualAnswers, never from answer wording or a job label.
     * Reflection keeps older frozen runtimes compilable. A second attempt retains the product's fixed repair prefix;
     * any source-ID hint is generated by the already-hash-checked product, not rewritten by this transport guard.
     */
    private static void verify52System(ObjectMapper json, Path runtime, JsonNode job, JsonNode input, JsonNode request)
            throws ReflectiveOperationException, IOException {
        if (request == null || !request.path("messages").isArray() || request.path("messages").size() != 2
                || !request.path("messages").path(0).path("role").asText().equals("system")
                || !request.path("messages").path(1).path("role").asText().equals("user")
                || !input.path("actualAnswers").isArray()) throw boundary("MISSING_ACTUAL_REQUEST");
        boolean writer = input.has("preparedRequirements");
        String field = writer ? "ENHANCE" : input.path("actualAnswers").isEmpty() ? "PREPARE" : "PREPARE_WITH_ANSWERS";
        String expected = (String) Class.forName("com.promptoptimizer.provider.domain.NaturalPromptEffectiveRegistryInstructions")
                .getField(field).get(null);
        int priorSameStage = 0;
        try (var paths = Files.list(runtime.resolve("traces"))) {
            for (Path path : paths.filter(item -> item.getFileName().toString().endsWith(".json")).toList()) {
                JsonNode prior = json.readTree(path.toFile());
                if (prior.path("jobId").asText().equals(job.path("id").asText())
                        && requestInput(json, prior.path("request")).has("preparedRequirements") == writer) priorSameStage++;
            }
        }
        String actual = request.path("messages").path(0).path("content").asText();
        if (priorSameStage == 0 ? !actual.equals(expected)
                : priorSameStage != 1 || !actual.startsWith(expected + REPAIR_PREFIX)) throw boundary("WRONG_SYSTEM_VARIANT");
    }

    private static Audit inspect(ObjectMapper json, Path runtime, JsonNode protocol, JsonNode job, JsonNode input)
            throws IOException, ReflectiveOperationException {
        String version = job.path("version").asText();
        if (!job.path("phase").asText().equals("GENERATION") || !REGISTRY_VERSIONS.contains(version))
            throw boundary("WRONG_STAGE");
        if (!input.isObject() || !input.path("strategyVersion").asText().equals(version)) throw boundary("WRONG_INPUT_VERSION");
        for (var fields = input.fieldNames(); fields.hasNext();) if (!INPUT_FIELDS.contains(fields.next())) throw boundary("UNKNOWN_INPUT_FIELD");
        ObjectNode core = originalInput(input);
        NaturalPromptInput original = naturalInput(json, core);
        if (!original.modelId().equals(protocol.path("modelId").asText())
                || !json.valueToTree(original.taskSources()).equals(core.path("taskSources"))) throw boundary("SOURCE_OR_MODEL_CATALOG_DRIFT");
        String originalHash = sha(json.writeValueAsBytes(core));
        List<Path> files;
        try (var paths = Files.list(runtime.resolve("traces"))) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".json")).sorted(Comparator.comparing(Path::toString)).toList();
        }
        JsonNode priorPreparation = null;
        String preparedTrace = null;
        boolean priorWriter = false;
        for (Path path : files) {
            JsonNode trace = json.readTree(path.toFile());
            if (trace == null || trace.path("jobId").asText().isBlank()) throw boundary("UNRESOLVED_TRACE");
            if (!trace.path("jobId").asText().equals(job.path("id").asText())) continue;
            JsonNode prior = requestInput(json, trace.path("request"));
            if (!originalInput(prior).equals(core)) throw boundary("ORIGINAL_INPUT_DRIFT");
            if (prior.has("preparedRequirements")) priorWriter = true;
            else { priorPreparation = trace; preparedTrace = path.getFileName().toString(); }
        }
        if (!input.has("preparedRequirements")) {
            if (input.has("taskScope") || priorWriter) throw boundary("PREPARATION_SEQUENCE_DRIFT");
            return new Audit("REQUIREMENT_PREPARATION", null, originalHash, null, 0);
        }
        if (!input.path("preparedRequirements").isArray() || !input.path("taskScope").isObject()
                || priorPreparation == null || priorPreparation.path("httpStatus").asInt() != 200
                || !priorPreparation.path("response").path("choices").path(0).path("finish_reason").asText().equals("stop"))
            throw boundary("PREPARATION_NOT_AVAILABLE");
        Object prepared = normalizeActualPreparation(json, original,
                priorPreparation.path("response").path("choices").path(0).path("message").path("content").asText());
        JsonNode expectedRequirements = json.valueToTree(prepared.getClass().getMethod("requirements").invoke(prepared));
        JsonNode expectedScope = json.valueToTree(prepared.getClass().getMethod("scope").invoke(prepared));
        if (!input.path("preparedRequirements").equals(expectedRequirements) || !input.path("taskScope").equals(expectedScope))
            throw boundary("DERIVED_REGISTRY_DRIFT");
        return new Audit("BODY_GENERATION", preparedTrace, originalHash,
                sha(json.writeValueAsBytes(expectedRequirements)), expectedRequirements.size());
    }

    /** Keep every original field, including complete context, actual answers, source IDs and explicit permissions. */
    private static ObjectNode originalInput(JsonNode input) throws IOException {
        if (!(input instanceof ObjectNode object)) throw boundary("MISSING_TASK_INPUT");
        ObjectNode core = object.deepCopy();
        core.remove(List.of("taskScope", "preparedRequirements"));
        return core;
    }

    /** Recreate the immutable product input solely to use its real source catalog and preparation checks. */
    private static NaturalPromptInput naturalInput(ObjectMapper json, ObjectNode core) throws IOException {
        ObjectNode data = core.deepCopy();
        data.remove(List.of("taskSources", "inputBoundary"));
        try { return json.treeToValue(data, NaturalPromptInput.class); }
        catch (RuntimeException failure) { throw boundary("INVALID_ORIGINAL_INPUT"); }
    }

    /** Parse only actual serialized task JSON, not instruction keywords or guessed writer content. */
    private static JsonNode requestInput(ObjectMapper json, JsonNode request) throws IOException {
        String user = request.path("messages").path(1).path("content").asText();
        int offset = user.indexOf('{');
        if (offset < 0) throw boundary("MISSING_TASK_INPUT");
        return json.readTree(user.substring(offset));
    }

    /** The HTTP factory always rejects network; only the runtime product readers and resolve method are used. */
    /** 只复用生产投影函数核对实际字段，不自行重建或修改发给模型的输入。 */
    private static JsonNode projectWriter70(JsonNode full) throws ReflectiveOperationException {
        var type = Class.forName("com.promptoptimizer.provider.infrastructure.openai.NaturalPromptWriterInput");
        var project = type.getDeclaredMethod("project", String.class, String.class, boolean.class, boolean.class, JsonNode.class);
        project.setAccessible(true);
        return (JsonNode) project.invoke(null, "70", "prompt.optimize", false, false, full);
    }

    private static Object normalizeActualPreparation(ObjectMapper json, NaturalPromptInput input, String content)
            throws ReflectiveOperationException, IOException {
        Object prepared = normalizePreparationPayload(json, input, content);
        return Set.of("67", "68", "69", "70").contains(input.strategyVersion()) ? prepared.getClass().getMethod("registry").invoke(prepared) : prepared;
    }

    /** 使用生产reader恢复同一响应，不增加HTTP，也不由评测端伪造补全建议。 */
    private static Object normalizePreparationPayload(ObjectMapper json, NaturalPromptInput input, String content)
            throws ReflectiveOperationException, IOException {
        RestClient noNetwork = RestClient.builder().requestFactory((uri, method) -> { throw new IOException("Offline guard cannot call a model"); }).build();
        var provider = new OpenAiCompatibleNaturalPromptProvider(noNetwork, json,
                new OpenAiCompatibleProperties(), (PlatformModelCatalog) null);
        Method unwrap = OpenAiCompatibleNaturalPromptProvider.class.getDeclaredMethod("unwrap", String.class);
        Method read = OpenAiCompatibleNaturalPromptProvider.class.getDeclaredMethod("readResponse", String.class);
        Field field = OpenAiCompatibleNaturalPromptProvider.class.getDeclaredField(
                Set.of("67", "68", "69", "70").contains(input.strategyVersion()) ? "completionPreparationReader" : "requirementPreparationReader");
        unwrap.setAccessible(true); read.setAccessible(true); field.setAccessible(true);
        JsonNode body = (JsonNode) read.invoke(provider, (String) unwrap.invoke(provider, content));
        Object preparation = ((ObjectReader) field.get(provider)).readValue(body);
        return preparation.getClass().getMethod("resolve", NaturalPromptInput.class).invoke(preparation, input);
    }

    /** Rejections are zero-upstream tooling events, never model traces or semantic failure verdicts. */
    private static void recordRejection(ObjectMapper json, Path runtime, JsonNode job, String rawCode) throws IOException {
        String code = rawCode != null && rawCode.matches("GUARD49_[A-Z_]+") ? rawCode : "GUARD49_BOUNDARY_CHECK_FAILED";
        String id = job.path("id").asText();
        if (!id.matches("[A-Z0-9_-]+")) id = "UNKNOWN_JOB";
        String version = REGISTRY_VERSIONS.contains(job.path("version").asText()) ? job.path("version").asText() : "49";
        Path directory = runtime.resolve("guard-events"); Files.createDirectories(directory);
        for (int index = 1; index <= 100; index++) {
            Path target = directory.resolve(id + "-" + String.format("%03d", index) + ".json");
            try {
                Files.write(target, json.writeValueAsBytes(java.util.Map.of("jobId", id, "phase", "GENERATION",
                        "version", version, "code", code, "upstreamRequestSent", false, "actualModelCalls", 0,
                        "classification", "TOOLING_OR_TRANSPORT_BOUNDARY_NOT_MODEL_SEMANTIC_FAILURE")), StandardOpenOption.CREATE_NEW);
                return;
            } catch (java.nio.file.FileAlreadyExistsException ignored) { }
        }
        throw boundary("GUARD_EVENT_LIMIT");
    }

    private static IOException boundary(String code) { return new IOException("GUARD49_" + code); }

    private static String sha(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.GeneralSecurityException failure) { throw boundary("HASH_UNAVAILABLE"); }
    }

    private NaturalRegistry49CallGuard() { }
}
