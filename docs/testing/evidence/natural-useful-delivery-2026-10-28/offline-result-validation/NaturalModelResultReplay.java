package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.RestClient;

/**
 * Replays frozen model results through actual product methods without invoking a provider or changing a candidate.
 * Only reports protocol/source/safety/coverage delivery; semantic correctness remains a separate review.
 */
public final class NaturalModelResultReplay {
    static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();
    private static final Set<String> INPUT_FIELDS = Set.of("rawPrompt", "context", "actualAnswers", "conversation", "mandatoryRules", "modelId", "planningContext", "strategyVersion");
    private static final Set<String> AUXILIARY_FIELDS = Set.of("taskSources", "taskScope", "inputBoundary", "protocolRepair");
    private final OpenAiCompatibleNaturalPromptProvider provider;
    private final NaturalEnhancementService application = new NaturalEnhancementService(null, null, null, null, null, null);
    private final Method unwrap = method(OpenAiCompatibleNaturalPromptProvider.class, "unwrap", String.class);
    private final Method readResponse = method(OpenAiCompatibleNaturalPromptProvider.class, "readResponse", String.class);
    private final Method requiredText = method(OpenAiCompatibleNaturalPromptProvider.class, "requiredText", JsonNode.class, String.class);
    private final Method understanding = method(OpenAiCompatibleNaturalPromptProvider.class, "understanding", JsonNode.class, NaturalPromptInput.class);
    private final Method validateBody = method(NaturalEnhancementService.class, "validateBody", NaturalPromptDraft.class, List.class, String.class);

    NaturalModelResultReplay() {
        // Even a programming error cannot make an HTTP call from this replayer.
        var client = RestClient.builder().requestFactory((uri, operation) -> { throw new AssertionError("HTTP is disabled in offline replay"); }).build();
        provider = new OpenAiCompatibleNaturalPromptProvider(client, JSON, new OpenAiCompatibleProperties(), (PlatformModelCatalog) null);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try { Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Frozen product method is unavailable: " + name, failure); }
    }

    static Object call(Method method, Object target, Object... arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String hash(String text) throws Exception { return hash(text.getBytes(StandardCharsets.UTF_8)); }
    static JsonNode read(Path path) throws Exception { return JSON.readTree(Files.readAllBytes(path)); }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    static void noHoldout(Path path) { require(!Arrays.asList(path.toString().replace('\\', '/').split("/")).stream().anyMatch(part -> part.startsWith("sealed-")), "Sealed material is outside this entry"); }

    /** Shared by legacy and49 replay; these are the frozen Provider's own parsers, never a JavaScript approximation. */
    JsonNode parseModelContent(String content) throws Throwable {
        return (JsonNode) call(readResponse, provider, call(unwrap, provider, content));
    }

    /** New protocol adapters reuse local strict readers without changing product visibility or invoking HTTP. */
    com.fasterxml.jackson.databind.ObjectReader strictReader(String fieldName) throws Exception {
        var field = OpenAiCompatibleNaturalPromptProvider.class.getDeclaredField(fieldName); field.setAccessible(true);
        return (com.fasterxml.jackson.databind.ObjectReader) field.get(provider);
    }

    /** Actual common application checks. A post-raw-failure assembled check is diagnostic only. */
    DraftCheck checkDraft(NaturalPromptDraft draft, NaturalPromptInput input) {
        List<Map<String, Object>> steps = new ArrayList<>();
        String current = "application.secondResolve", assembled = null;
        NaturalTaskUnderstanding resolved = null;
        Map<String, Object> rejection = null;
        try {
            resolved = draft.taskUnderstanding().resolve(input); steps.add(Map.of("stage", current, "status", "PASS"));
            current = "application.validateBodySafetyAndAssemble";
            assembled = (String) call(validateBody, application, draft, input.mandatoryRules(), input.strategyVersion());
            steps.add(Map.of("stage", current, "status", "PASS"));
            current = "product.validateEnhancement.raw";
            resolved.review().validateEnhancement(resolved, draft.optimizedPrompt().strip()); steps.add(Map.of("stage", current, "status", "PASS"));
            current = "product.validateEnhancement.assembled";
            resolved.review().validateEnhancement(resolved, assembled); steps.add(Map.of("stage", current, "status", "PASS"));
        } catch (Throwable failure) {
            rejection = new LinkedHashMap<>(); rejection.put("stage", current); rejection.put("status", "FAIL"); rejection.putAll(reason(failure)); steps.add(rejection);
            if (current.equals("product.validateEnhancement.raw") && resolved != null && assembled != null) {
                Map<String, Object> diagnostic = new LinkedHashMap<>(); diagnostic.put("stage", "product.validateEnhancement.assembled"); diagnostic.put("diagnosticOnlyAfterFirstRejection", true);
                try { resolved.review().validateEnhancement(resolved, assembled); diagnostic.put("status", "PASS"); }
                catch (Throwable secondFailure) { diagnostic.put("status", "FAIL"); diagnostic.putAll(reason(secondFailure)); }
                steps.add(diagnostic);
            }
        }
        return new DraftCheck(assembled, steps, rejection);
    }

    record DraftCheck(String assembled, List<Map<String, Object>> steps, Map<String, Object> firstRejection) { }

    /** Exception values are not copied: parser locations and fixed product messages suffice for diagnosis. */
    static Map<String, Object> reason(Throwable failure) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exception", failure.getClass().getName());
        if (failure instanceof ProviderException product) {
            result.put("type", product.getFailureType().name()); result.put("message", product.getMessage());
        } else if (failure instanceof JsonProcessingException json) {
            result.put("type", "JSON_PROTOCOL_INVALID");
            result.put("message", "原产品JSON解析拒绝；未复制可能含用户值的异常文本");
            if (json.getLocation() != null) { result.put("line", json.getLocation().getLineNr()); result.put("column", json.getLocation().getColumnNr()); }
        } else if (failure instanceof IllegalArgumentException) {
            result.put("type", "INVALID_RESPONSE"); result.put("message", "自然正文或框架标识无效");
        } else { result.put("type", "REPLAY_OR_INPUT_ERROR"); result.put("message", "离线入口或输入证据异常；未复制不受控异常文本"); }
        return result;
    }

    private Map<String, Object> validate(JsonNode job, JsonNode result, JsonNode trace, String resultHash,
            String traceHash, String jobsHash) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        String id = job.path("id").asText(); report.put("jobId", id);
        report.put("semanticReviewRequired", true); report.put("semanticCorrectnessClaimed", false);
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("jobsSha256", jobsHash); provenance.put("resultSha256", resultHash); provenance.put("traceSha256", traceHash);
        report.put("inputProvenance", provenance);
        List<Map<String, Object>> steps = new ArrayList<>(); report.put("steps", steps);
        String current = "closedEvidence";
        JsonNode parsed = null; NaturalPromptInput input = null;
        try {
            require(result.path("id").asText().equals(id), "Result identity mismatch");
            require(trace.path("jobId").asText().equals(id), "Trace identity mismatch");
            JsonNode request = job.path("approvedModelRequest");
            require(request.isObject() && request.equals(trace.path("request")), "Actual request differs from frozen job");
            provenance.put("approvedRequestSha256", hash(JSON.writeValueAsBytes(request)));
            int status = result.path("status").asInt(-1); report.put("httpStatus", status);
            report.put("upstreamHttpStatus", trace.path("httpStatus").asInt(-1));
            if (status != 200 || trace.path("httpStatus").asInt(-1) != 200) { report.put("outcome", "HTTP_OR_UPSTREAM_FAILURE"); return report; }
            JsonNode choices = trace.path("response").path("choices");
            require(choices.isArray() && choices.size() == 1, "Upstream choices are missing or ambiguous");
            String finish = result.path("body").path("data").path("finishReason").asText("");
            String upstreamFinish = choices.get(0).path("finish_reason").asText("");
            report.put("finishReason", finish); report.put("upstreamFinishReason", upstreamFinish);
            require(finish.equals(upstreamFinish), "Finish reason evidence conflict");
            if (!finish.equals("stop")) { report.put("outcome", "INCOMPLETE_RESPONSE"); return report; }
            if (!choices.get(0).path("message").path("refusal").asText("").isBlank()) { report.put("outcome", "MODEL_REFUSAL"); return report; }
            JsonNode contentNode = result.path("body").path("data").path("content");
            require(contentNode.isTextual(), "Actual body.data.content is unavailable");
            String content = contentNode.asText();
            require(content.equals(choices.get(0).path("message").path("content").asText()), "Result and trace content conflict");
            provenance.put("actualContentSha256", hash(content));

            current = "frozenInputReconstruction";
            JsonNode messages = request.path("messages");
            require(messages.isArray() && messages.size() == 2 && messages.get(1).path("role").asText().equals("user"), "Unsupported frozen message shape");
            String user = messages.get(1).path("content").asText();
            require(user.indexOf('{') >= 0, "Frozen user payload has no JSON object");
            ObjectNode payload = (ObjectNode) JSON.readTree(user.substring(user.indexOf('{')));
            for (Iterator<String> fields = payload.fieldNames(); fields.hasNext();) {
                String field = fields.next(); require(INPUT_FIELDS.contains(field) || AUXILIARY_FIELDS.contains(field), "Unsupported transport field in frozen input");
            }
            ObjectNode record = payload.deepCopy(); record.retain(INPUT_FIELDS);
            input = JSON.treeToValue(record, NaturalPromptInput.class);
            require(NaturalPromptInstructions.supports(input.strategyVersion()), "Frozen input version is unsupported by runtime");
            require(payload.path("taskSources").equals(JSON.valueToTree(input.taskSources())), "Server source catalogue differs from frozen payload");
            provenance.put("userMessageSha256", hash(user));
            provenance.put("naturalInputSha256", hash(JSON.writeValueAsBytes(record)));
            provenance.put("strategyVersion", input.strategyVersion());
            provenance.put("sourceCount", input.taskSources().size()); provenance.put("actualAnswerCount", input.actualAnswers().size());
            provenance.put("protocolRepairPresent", payload.has("protocolRepair"));
            provenance.put("mandatoryRuleCount", input.mandatoryRules().size());
            steps.add(Map.of("stage", current, "status", "PASS"));

            current = "product.unwrapAndReadResponse";
            parsed = (JsonNode) call(readResponse, provider, call(unwrap, provider, content));
            require(parsed != null && parsed.isObject(), "Product response is not a JSON object");
            steps.add(Map.of("stage", current, "status", "PASS"));
            current = "product.requiredOptimizedPrompt";
            String optimized = (String) call(requiredText, provider, parsed, "optimizedPrompt");
            provenance.put("providerOptimizedPromptSha256", hash(optimized));
            steps.add(Map.of("stage", current, "status", "PASS"));
            current = "product.understandingAndResolve";
            var task = (NaturalTaskUnderstanding) call(understanding, provider, parsed, input);
            steps.add(Map.of("stage", current, "status", "PASS"));
            current = "product.framework";
            String framework = (String) call(requiredText, provider, parsed, "framework");
            var draft = new NaturalPromptDraft(optimized, NaturalPromptDraft.Framework.valueOf(framework), new ProviderMetadata("offline", "offline", false), task);
            steps.add(Map.of("stage", current, "status", "PASS"));
            DraftCheck checked = checkDraft(draft, input); steps.addAll(checked.steps());
            if (checked.assembled() != null) provenance.put("assembledBodySha256", hash(checked.assembled()));
            if (checked.firstRejection() == null) report.put("outcome", "VALID_PRODUCT_DELIVERY_PROTOCOL_ONLY");
            else { report.put("outcome", "REJECTED_BY_PRODUCT"); report.put("firstRejection", checked.firstRejection()); }
        } catch (Throwable failure) {
            var detail = reason(failure);
            Map<String, Object> failed = new LinkedHashMap<>(); failed.put("stage", current); failed.put("status", "FAIL"); failed.putAll(detail);
            steps.add(failed); report.put("firstRejection", failed);
            report.put("outcome", current.equals("closedEvidence") || current.equals("frozenInputReconstruction") ? "INPUT_OR_EVIDENCE_ERROR" : "REJECTED_BY_PRODUCT");
        }
        if (parsed != null && input != null) {
            report.put("deterministicRepairDiagnostics", NaturalPromptRepairContext.inspect(JSON, parsed, input, false).stream()
                    .map(issue -> Map.of("code", issue.code(), "path", issue.path(), "sourceId", issue.sourceId() == null ? "" : issue.sourceId())).toList());
        }
        return report;
    }

    /** All locked originals are hashed before any candidate is interpreted or any report is written. */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>(); Set<String> selected = new LinkedHashSet<>();
        for (int index = 0; index < args.length; index += 2) {
            require(index + 1 < args.length, "Missing command-line value");
            if (args[index].equals("--job")) selected.add(args[index + 1]); else options.put(args[index], args[index + 1]);
        }
        Path repository = Path.of(options.get("--repo")).toAbsolutePath().normalize();
        Path stage = Path.of(options.get("--stage")).toAbsolutePath().normalize();
        Path output = Path.of(options.get("--output")).toAbsolutePath().normalize();
        noHoldout(stage); noHoldout(output);
        noHoldout(stage.toRealPath());
        require(stage.startsWith(repository) && output.startsWith(repository) && !output.startsWith(stage), "Paths must stay in the workspace; report must stay outside the closed stage");
        require(!Files.exists(output), "Report already exists; choose a new output path");
        JsonNode closure = read(stage.resolve("closure.json"));
        require(closure.path("status").asText().equals("CLOSED") && closure.path("entryLockedByClosure").asBoolean(), "Stage is not closed and locked");
        Map<Path, String> originals = new HashMap<>(); List<JsonNode> traces = new ArrayList<>(); Map<String, String> traceHashes = new HashMap<>();
        for (JsonNode file : closure.path("files")) {
            Path path = repository.resolve(file.path("path").asText()).toAbsolutePath().normalize(); noHoldout(path);
            require(path.startsWith(stage) && Files.exists(path) && path.toRealPath().startsWith(stage.toRealPath()), "Closure path is missing or escapes its stage");
            String digest = hash(Files.readAllBytes(path)); require(digest.equals(file.path("sha256").asText()), "Closed original digest mismatch"); originals.put(path, digest);
            if (path.startsWith(stage.resolve("traces"))) { JsonNode trace = read(path); traces.add(trace); traceHashes.put(trace.path("jobId").asText(), digest); }
        }
        require(!originals.isEmpty() && traces.size() == closure.path("actualCalls").asInt(), "Closed model-call evidence is incomplete");
        Path jobsPath = stage.resolve("jobs.json"); JsonNode jobs = read(jobsPath); require(jobs.isArray(), "Frozen jobs must be an array");
        var runner = new NaturalModelResultReplay(); List<Map<String, Object>> reports = new ArrayList<>(); Set<String> found = new HashSet<>();
        for (JsonNode job : jobs) {
            String id = job.path("id").asText(); if (!selected.isEmpty() && !selected.contains(id)) continue; found.add(id);
            require(id.matches("[A-Za-z0-9_-]+"), "Unsafe frozen job identifier");
            Path resultPath = stage.resolve("results/" + id + ".json");
            List<JsonNode> matching = traces.stream().filter(trace -> trace.path("jobId").asText().equals(id)).toList();
            if (!originals.containsKey(resultPath) || matching.size() != 1) {
                reports.add(Map.of("jobId", id, "outcome", "MISSING_OR_AMBIGUOUS_RESULT_EVIDENCE", "semanticCorrectnessClaimed", false));
                continue;
            }
            reports.add(runner.validate(job, read(resultPath), matching.getFirst(), originals.get(resultPath), traceHashes.get(id), hash(Files.readAllBytes(jobsPath))));
        }
        require(selected.isEmpty() || found.containsAll(selected), "Requested job is not in the frozen batch");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("modelCallsMadeByReplay", 0); report.put("candidateEdited", false);
        report.put("scope", "Protocol, exact sources, all answer-source linkage, body safety, and raw/assembled quote guards only. Factual/intent/condition completeness requires independent semantic review.");
        report.put("stage", repository.relativize(stage).toString());
        report.put("closureSha256", hash(Files.readAllBytes(stage.resolve("closure.json"))));
        report.put("protocolSha256", hash(Files.readAllBytes(stage.resolve("protocol.json"))));
        report.put("mapperProfile", "Spring Jackson2ObjectMapperBuilder defaults; Provider constructs its original strict response reader internally");
        Map<String, String> classes = new LinkedHashMap<>();
        for (Class<?> type : List.of(OpenAiCompatibleNaturalPromptProvider.class, NaturalTaskUnderstanding.class, NaturalTaskReview.class, NaturalEnhancementService.class, NaturalPromptRepairContext.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { require(stream != null, "Loaded product class is unavailable"); classes.put(type.getName(), hash(stream.readAllBytes())); }
        }
        report.put("loadedProductClassSha256", classes); report.put("results", reports);
        try (var stream = NaturalModelResultReplay.class.getResourceAsStream("NaturalModelResultReplay.class")) {
            require(stream != null, "Replayer class is unavailable"); report.put("replayerClassSha256", hash(stream.readAllBytes()));
        }
        Files.createDirectories(output.getParent()); Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(JSON.writeValueAsString(Map.of("checked", reports.size(), "outcomes", reports.stream().map(item -> Map.of("jobId", item.get("jobId"), "outcome", item.get("outcome"))).toList(), "modelCalls", 0)));
    }
}
