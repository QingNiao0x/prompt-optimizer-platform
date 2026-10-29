package com.promptoptimizer.provider.infrastructure.openai;

import static com.promptoptimizer.provider.infrastructure.openai.NaturalModelResultReplay.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.provider.domain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A finite49/50/51 adapter over the existing offline product checks. Real preparation responses are
 * resolved before their corresponding writer response; no guessed or hand-built registry is accepted.
 */
public final class NaturalRegistry49ResultReplay {
    private static final Set<String> VERSIONS = Set.of("49", "50", "51");
    private static final Set<String> INPUT = Set.of("rawPrompt", "context", "actualAnswers", "conversation", "mandatoryRules", "modelId", "planningContext", "strategyVersion");
    private static final Set<String> AUXILIARY = Set.of("inputBoundary", "taskSources", "taskScope", "preparedRequirements");
    private final NaturalModelResultReplay shared = new NaturalModelResultReplay();
    private final ObjectReader preparationReader;
    private final ObjectReader writerReader;

    private NaturalRegistry49ResultReplay() throws Exception {
        preparationReader = shared.strictReader("requirementPreparationReader");
        writerReader = shared.strictReader("referencedEnhancementReader");
    }

    private record Trace(Path path, JsonNode value, String sha256) { }
    private record Input(ObjectNode payload, NaturalPromptInput value, ObjectNode record) { }

    /** Reconstruct only the actual original record; derived phase data remains separately auditable. */
    private Input input(JsonNode request) throws Exception {
        JsonNode messages = request.path("messages");
        require(messages.isArray() && messages.size() == 2 && messages.get(1).path("role").asText().equals("user"), "Unsupported registry request shape");
        String user = messages.get(1).path("content").asText(); require(user.indexOf('{') >= 0, "Missing original JSON input");
        JsonNode tree = JSON.readTree(user.substring(user.indexOf('{'))); require(tree.isObject(), "Original input is not an object");
        ObjectNode payload = (ObjectNode) tree;
        for (Iterator<String> fields = payload.fieldNames(); fields.hasNext();) {
            String name = fields.next(); require(INPUT.contains(name) || AUXILIARY.contains(name), "Unexpected registry input field");
        }
        ObjectNode record = payload.deepCopy(); record.retain(INPUT);
        NaturalPromptInput value = JSON.treeToValue(record, NaturalPromptInput.class);
        require(VERSIONS.contains(value.strategyVersion()), "Runtime input is not finite49/50/51");
        require(payload.path("taskSources").equals(JSON.valueToTree(value.taskSources())), "Original source catalogue differs");
        return new Input(payload, value, record);
    }

    /** Reflect only newer guidance when requested so the same source still compiles against frozen49 runtimes. */
    private static String preparationInstruction(String version) throws ReflectiveOperationException {
        if (version.equals("49")) return NaturalPromptRequirementRegistryInstructions.PREPARE;
        require(version.equals("50") || version.equals("51"), "Unsupported registry guidance version");
        String type = version.equals("51") ? "NaturalPromptRegistryDiagnosticInstructions" : "NaturalPromptContextBoundaryRegistryInstructions";
        return (String) Class.forName("com.promptoptimizer.provider.domain." + type)
                .getField("PREPARE").get(null);
    }

    /** Only51 uses the actual writer diagnostic reader; preparation and49/50 remain strict. */
    ObjectReader writerReaderForVersion(String version) throws Exception {
        require(VERSIONS.contains(version), "Unsupported finite registry writer version");
        return version.equals("51") ? shared.strictReader("referencedDiagnosticEnhancementReader") : writerReader;
    }

    /** Match product phase mapping: strict DTO errors are INVALID_RESPONSE, original source exceptions stay exact. */
    private NaturalPromptRequirementRegistry.Prepared prepare(JsonNode body, NaturalPromptInput input) throws Exception {
        try { return ((NaturalPromptRequirementRegistry.Preparation) preparationReader.readValue(body)).resolve(input); }
        catch (IOException | IllegalArgumentException failure) { throw new ProviderException(ProviderFailureType.INVALID_RESPONSE, "自然原要求准备协议无效", false); }
    }

    private NaturalPromptReferencedEnhancement.Normalized normalize(JsonNode body, NaturalPromptInput input,
            NaturalPromptRequirementRegistry.Prepared prepared) throws Exception {
        try {
            NaturalPromptReferencedEnhancement response = writerReaderForVersion(input.strategyVersion()).readValue(body);
            return response.normalize(input, new ProviderMetadata("offline", "offline", false), prepared);
        } catch (IOException | IllegalArgumentException failure) {
            throw new ProviderException(ProviderFailureType.INVALID_RESPONSE, "自然原要求引用正文协议无效", false);
        }
    }

    /** Keep all attempts in recorded order; a successful earlier attempt cannot be replaced by a preferred later sample. */
    private Map<String, Object> replay(JsonNode job, JsonNode platformResult, List<Trace> traces, String jobsHash,
            String resultHash) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>(); String jobId = job.path("id").asText();
        String version = job.path("version").asText();
        String expectedPreparation = preparationInstruction(version);
        String expectedWriter = NaturalPromptInstructions.enhancementInstruction(version);
        report.put("jobId", jobId); report.put("semanticReviewRequired", true); report.put("semanticCorrectnessClaimed", false);
        report.put("jobsSha256", jobsHash); report.put("platformResultSha256", resultHash);
        report.put("approvedPublicDtoSha256", hash(JSON.writeValueAsBytes(job.path("approvedEnhancementInput"))));
        List<Map<String, Object>> attempts = new ArrayList<>(); report.put("attempts", attempts);
        NaturalPromptRequirementRegistry.Prepared prepared = null; Trace preparedTrace = null;
        Input original = null; NaturalModelResultReplay.DraftCheck accepted = null;
        int prepareCount = 0, writerCount = 0; boolean writerStarted = false, terminalFailure = false;
        for (Trace trace : traces) {
            require(accepted == null, "Trace exists after an already accepted writer; cannot select a preferred sample");
            require(!terminalFailure, "Trace exists after a non-retryable upstream/refusal failure");
            JsonNode raw = trace.value(); JsonNode request = raw.path("request");
            Input actual = input(request);
            require(actual.value().strategyVersion().equals(version), "Actual input and frozen job versions differ");
            if (original == null) original = actual;
            require(original.record().equals(actual.record()), "Original complete input changed across attempts or phases");
            ObjectNode base = actual.payload().deepCopy(); base.remove(List.of("taskScope", "preparedRequirements"));
            ObjectNode firstBase = original.payload().deepCopy(); firstBase.remove(List.of("taskScope", "preparedRequirements"));
            require(base.equals(firstBase), "Original auxiliary/source input changed across attempts or phases");
            require(actual.value().rawPrompt().equals(job.path("approvedEnhancementInput").path("rawPrompt").asText()), "Raw prompt differs from frozen public DTO");
            JsonNode requestedModel = job.path("approvedEnhancementInput").path("modelId");
            if (requestedModel.isTextual() && !requestedModel.asText().isBlank()) require(requestedModel.asText().equals(actual.value().modelId()), "Public model differs from frozen DTO");
            String system = request.path("messages").get(0).path("content").asText();
            boolean preparation = system.startsWith(expectedPreparation);
            boolean writer = system.startsWith(expectedWriter);
            require(preparation != writer, "Unknown or ambiguous frozen registry phase guidance");
            String phase = preparation ? "REQUIREMENT_PREPARATION" : "BODY_GENERATION";
            require(raw.path("registry49").path("callStage").asText().equals(phase), "Recorded registry phase and product guidance differ");
            if (preparation) {
                require(!writerStarted && prepared == null && ++prepareCount <= 2, "Unexpected preparation attempt order/count");
                require(!actual.payload().has("preparedRequirements") && !actual.payload().has("taskScope"), "Preparation already contains derived data");
            } else {
                require(prepared != null && ++writerCount <= 2, "Writer has no actual accepted preparation or exceeds attempts"); writerStarted = true;
                require(raw.path("registry49").path("preparedTrace").asText().equals(preparedTrace.path().getFileName().toString()), "Writer points to a different preparation original");
                require(actual.payload().path("preparedRequirements").equals(JSON.valueToTree(prepared.requirements())), "Writer registry differs from actual preparation.resolve");
                require(actual.payload().path("taskScope").equals(JSON.valueToTree(prepared.scope())), "Writer scope differs from actual preparation.resolve");
            }
            Map<String, Object> item = new LinkedHashMap<>(); attempts.add(item);
            item.put("trace", trace.path().getFileName().toString()); item.put("traceSha256", trace.sha256()); item.put("phase", phase);
            item.put("upstreamHttpStatus", raw.path("httpStatus").asInt(-1));
            item.put("requestSha256", hash(JSON.writeValueAsBytes(request))); item.put("originalNaturalInputSha256", hash(JSON.writeValueAsBytes(actual.record())));
            List<Map<String, Object>> steps = new ArrayList<>(); item.put("steps", steps);
            String current = "upstreamCompletion";
            try {
                if (raw.path("httpStatus").asInt(-1) != 200) { item.put("outcome", "UPSTREAM_HTTP_FAILURE"); terminalFailure = true; continue; }
                JsonNode choices = raw.path("response").path("choices"); require(choices.isArray() && choices.size() == 1, "Missing or ambiguous upstream choice");
                String finish = choices.get(0).path("finish_reason").asText(""); item.put("finishReason", finish);
                if (!finish.equals("stop")) { item.put("outcome", "INCOMPLETE_RESPONSE_OR_MISSING_FINISH_EVIDENCE"); continue; }
                if (!choices.get(0).path("message").path("refusal").asText("").isBlank()) { item.put("outcome", "MODEL_REFUSAL"); terminalFailure = true; continue; }
                JsonNode contentNode = choices.get(0).path("message").path("content");
                String content = contentNode.asText(""); item.put("actualContentSha256", hash(content));
                current = "product.unwrapAndReadResponse"; JsonNode body;
                try { body = shared.parseModelContent(content); }
                catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    item.put("parserDiagnostic", reason(failure));
                    throw new ProviderException(ProviderFailureType.INVALID_RESPONSE, "自然链路响应JSON无效", false);
                }
                if (body == null || !body.isObject()) throw new ProviderException(ProviderFailureType.INVALID_RESPONSE, "自然链路响应不是JSON对象", false);
                steps.add(Map.of("stage", current, "status", "PASS"));
                if (preparation) {
                    current = "product.strictPreparationReaderAndResolve";
                    prepared = prepare(body, actual.value()); preparedTrace = trace;
                    item.put("outcome", "PREPARATION_SOURCE_PROTOCOL_VALID_ONLY");
                    item.put("registrySha256", hash(JSON.writeValueAsBytes(prepared.requirements())));
                    item.put("reqIds", prepared.requirements().stream().map(NaturalPromptRequirementRegistry.Entry::reqId).toList());
                    steps.add(Map.of("stage", current, "status", "PASS"));
                } else {
                    current = version.equals("51") ? "product.version51WriterReaderAndNormalize" : "product.strictWriterReaderAndNormalize";
                    var normalized = normalize(body, actual.value(), prepared);
                    steps.add(Map.of("stage", current, "status", "PASS"));
                    item.put("preparedTraceSha256", preparedTrace.sha256());
                    item.put("normalizedBindings", normalized.bindings());
                    item.put("canonicalUnderstandingSha256", hash(JSON.writeValueAsBytes(normalized.draft().taskUnderstanding())));
                    item.put("providerOptimizedPromptSha256", hash(normalized.draft().optimizedPrompt()));
                    var checked = shared.checkDraft(normalized.draft(), actual.value()); steps.addAll(checked.steps());
                    if (checked.assembled() != null) item.put("assembledBodySha256", hash(checked.assembled()));
                    if (checked.firstRejection() != null) { item.put("outcome", "REJECTED_BY_PRODUCT"); item.put("firstRejection", checked.firstRejection()); }
                    else { item.put("outcome", "VALID_PRODUCT_DELIVERY_PROTOCOL_ONLY"); accepted = checked; }
                }
            } catch (Throwable failure) {
                Map<String, Object> rejected = new LinkedHashMap<>(); rejected.put("stage", current); rejected.put("status", "FAIL"); rejected.putAll(reason(failure));
                steps.add(rejected); item.put("outcome", "REJECTED_BY_PRODUCT"); item.put("firstRejection", rejected);
                if (failure instanceof NaturalTaskReview.MissingSources missing) item.put("missingServerSourceIds", new TreeSet<>(missing.sourceIds()));
            }
        }
        require(platformResult.path("id").asText().equals(jobId), "Platform result identity mismatch");
        int platformStatus = platformResult.path("status").asInt(-1); report.put("platformHttpStatus", platformStatus);
        report.put("preparationAttempts", prepareCount); report.put("writerAttempts", writerCount);
        if (accepted == null) {
            require(platformStatus != 200, "Platform claims delivery but actual frozen product replay rejected every writer");
            report.put("outcome", prepared == null ? "NO_VALID_PREPARATION" : writerStarted ? "REJECTED_BY_PRODUCT" : "WRITER_NOT_SENT");
        } else if (platformStatus != 200) report.put("outcome", "PRODUCT_CHECKS_ACCEPTED_BUT_PLATFORM_NOT_DELIVERED");
        else {
            require(platformResult.path("body").path("data").path("optimizedPrompt").asText().equals(accepted.assembled()), "Delivered body differs from actual product assembly");
            report.put("outcome", "VALID_PRODUCT_DELIVERY_PROTOCOL_ONLY");
        }
        return report;
    }

    /** Read only locked originals; all output remains outside the closed stage and never contains hidden reasoning. */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>(); Set<String> selected = new LinkedHashSet<>();
        for (int index = 0; index < args.length; index += 2) {
            require(index + 1 < args.length, "Missing command-line value");
            if (args[index].equals("--job")) selected.add(args[index + 1]); else options.put(args[index], args[index + 1]);
        }
        Path repository = Path.of(options.get("--repo")).toAbsolutePath().normalize(), stage = Path.of(options.get("--stage")).toAbsolutePath().normalize(), output = Path.of(options.get("--output")).toAbsolutePath().normalize();
        noHoldout(stage); noHoldout(stage.toRealPath()); noHoldout(output);
        require(stage.startsWith(repository) && output.startsWith(repository) && !output.startsWith(stage) && !Files.exists(output), "Unsafe or existing replay output path");
        JsonNode closure = read(stage.resolve("closure.json")); require(closure.path("status").asText().equals("CLOSED") && closure.path("entryLockedByClosure").asBoolean(), "Stage is not CLOSED/locked");
        Map<Path, String> originals = new HashMap<>(); List<Trace> traces = new ArrayList<>();
        for (JsonNode file : closure.path("files")) {
            Path path = repository.resolve(file.path("path").asText()).toAbsolutePath().normalize(); noHoldout(path);
            require(path.startsWith(stage) && Files.exists(path) && path.toRealPath().startsWith(stage.toRealPath()), "Closed original path escapes stage");
            String digest = hash(Files.readAllBytes(path)); require(digest.equals(file.path("sha256").asText()), "Closed original hash mismatch"); originals.put(path, digest);
            if (path.startsWith(stage.resolve("traces"))) traces.add(new Trace(path, read(path), digest));
        }
        require(traces.size() == closure.path("actualCalls").asInt(), "Closed model-call evidence is incomplete");
        traces.sort(Comparator.comparing(trace -> trace.path().getFileName().toString()));
        Path jobsPath = stage.resolve("jobs.json"); JsonNode jobs = read(jobsPath); require(jobs.isArray(), "Jobs are not an array");
        String jobsHash = hash(Files.readAllBytes(jobsPath)); Set<String> found = new HashSet<>(); List<Map<String, Object>> reports = new ArrayList<>();
        var runner = new NaturalRegistry49ResultReplay();
        for (JsonNode job : jobs) {
            String id = job.path("id").asText(); if (!selected.isEmpty() && !selected.contains(id)) continue; found.add(id);
            require(id.matches("[A-Za-z0-9_-]+") && VERSIONS.contains(job.path("version").asText()) && job.has("approvedEnhancementInput"), "Unsupported finite49/50/51 frozen job");
            Path resultPath = stage.resolve("results/" + id + ".json");
            if (!originals.containsKey(resultPath)) { reports.add(Map.of("jobId", id, "outcome", "MISSING_RESULT", "semanticCorrectnessClaimed", false)); continue; }
            List<Trace> matching = traces.stream().filter(trace -> trace.value().path("jobId").asText().equals(id)).toList();
            require(!matching.isEmpty(), "No actual registry phase originals for job");
            reports.add(runner.replay(job, read(resultPath), matching, jobsHash, originals.get(resultPath)));
        }
        require(selected.isEmpty() || found.containsAll(selected), "Requested job is not frozen");
        Map<String, Object> report = new LinkedHashMap<>(); report.put("modelCallsMadeByReplay", 0); report.put("candidateEdited", false);
        report.put("stage", repository.relativize(stage).toString()); report.put("closureSha256", hash(Files.readAllBytes(stage.resolve("closure.json")))); report.put("protocolSha256", hash(Files.readAllBytes(stage.resolve("protocol.json"))));
        boolean has51 = false; for (JsonNode job : jobs) has51 |= job.path("version").asText().equals("51");
        report.put("scope", has51 ? "Actual51 strict preparation.resolve, immutable registry linkage, actual version51 diagnostic writer reader and normalize, original sources/all QA and application safety/raw/assembled guards. Scalar diagnostic compatibility is not semantic completeness or adoption."
                : "Actual49/50 strict preparation.resolve, immutable registry linkage, writer.normalize, original sources/all QA and original application safety/raw/assembled guards. Source/quote legality is not semantic completeness or adoption.");
        Map<String, String> classes = new LinkedHashMap<>();
        for (Class<?> type : List.of(OpenAiCompatibleNaturalPromptProvider.class, NaturalPromptRequirementRegistry.class, NaturalPromptReferencedEnhancement.class, NaturalTaskUnderstanding.class, NaturalTaskReview.class, NaturalEnhancementService.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { require(stream != null, "Loaded product class missing"); classes.put(type.getName(), hash(stream.readAllBytes())); }
        }
        report.put("loadedProductClassSha256", classes); report.put("results", reports);
        Files.createDirectories(output.getParent()); Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(JSON.writeValueAsString(Map.of("checked", reports.size(), "outcomes", reports.stream().map(item -> Map.of("jobId", item.get("jobId"), "outcome", item.get("outcome"))).toList(), "modelCalls", 0)));
    }
}
