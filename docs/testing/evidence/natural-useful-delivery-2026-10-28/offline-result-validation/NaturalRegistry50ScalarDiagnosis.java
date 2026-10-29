package com.promptoptimizer.provider.infrastructure.openai;

import static com.promptoptimizer.provider.infrastructure.openai.NaturalModelResultReplay.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.provider.domain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Frozen50 reader-only differential diagnosis. Copies are explicitly counterfactual and never replace originals.
 * No product classes, stage entry, candidate, registry or original verdict is rewritten; HTTP is disabled by the shared helper.
 */
public final class NaturalRegistry50ScalarDiagnosis {
    private static final Set<String> INPUT = Set.of("rawPrompt", "context", "actualAnswers", "conversation", "mandatoryRules", "modelId", "planningContext", "strategyVersion");
    private final NaturalModelResultReplay shared = new NaturalModelResultReplay();
    private final ObjectReader strictWriter = shared.strictReader("referencedEnhancementReader");
    private final ObjectReader strictPreparation = shared.strictReader("requirementPreparationReader");

    private NaturalRegistry50ScalarDiagnosis() throws Exception { }

    /** Copy the actual frozen reader's owning mapper, including all coercion/type rules, rather than reconstructing it. */
    private ObjectReader copyReader(ObjectReader original, Class<?> type, DeserializationProblemHandler handler) {
        require(original.getFactory().getCodec() instanceof ObjectMapper, "Frozen reader has no owning mapper");
        ObjectMapper mapper = (ObjectMapper) original.getFactory().getCodec();
        require(mapper.getDeserializationConfig() == original.getConfig(), "Reader has additional configuration; copying would be approximate");
        return mapper.copy().addHandler(handler).readerFor(type);
    }

    /** Prototype only: reserve actual protocol component names and derived server inputs, then delegate the unchanged24 boundary. */
    private static final class Guarded24 extends DeserializationProblemHandler {
        private final NaturalPromptDiagnosticFieldHandler original24 = new NaturalPromptDiagnosticFieldHandler();
        private final Set<String> reserved = new HashSet<>(Set.of("preparedRequirements", "taskScope", "inputBoundary",
                "executionResponsibilities", "materialVisibility", "completionSuggestions", "fidelityDraft", "fidelityFindings",
                "fidelityEditTargets", "protocolRepair"));

        Guarded24() {
            for (Class<?> type : List.of(NaturalPromptRequirementRegistry.class, NaturalPromptReferencedEnhancement.class,
                    NaturalTaskScope.class, NaturalPromptPhasePayloads.class, NaturalTaskUnderstanding.class, NaturalTaskReview.class,
                    NaturalPromptInput.class, NaturalPromptDraft.class, ProviderMetadata.class)) collect(type);
        }

        private void collect(Class<?> type) {
            if (type.isRecord()) for (var field : type.getRecordComponents()) reserved.add(field.getName());
            for (Class<?> nested : type.getDeclaredClasses()) collect(nested);
        }

        @Override public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
                JsonDeserializer<?> deserializer, Object bean, String name) throws IOException {
            if (reserved.contains(name)) return false;
            return original24.handleUnknownProperty(context, parser, deserializer, bean, name);
        }
    }

    /** Keep field paths and stable product reasons; do not print raw candidate/diagnostic values. */
    private static Map<String, Object> failure(Throwable failure) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("exception", failure.getClass().getName());
        if (failure instanceof ProviderException provider) { result.put("type", provider.getFailureType().name()); result.put("message", provider.getMessage()); }
        if (failure instanceof JsonMappingException mapping) {
            var scanner = new SensitiveValueScanner();
            result.put("fieldPath", mapping.getPath().stream().map(part -> part.getFieldName() == null ? "[" + part.getIndex() + "]"
                    : scanner.containsCredential(part.getFieldName()) ? "<redacted>" : part.getFieldName()).toList());
        }
        return result;
    }

    private Map<String, Object> writer(ObjectReader reader, JsonNode candidate, NaturalPromptInput input,
            NaturalPromptRequirementRegistry.Prepared registry) {
        Map<String, Object> result = new LinkedHashMap<>(); String step = "strictWriterReader";
        try {
            NaturalPromptReferencedEnhancement payload = reader.readValue(candidate);
            step = "normalize"; var normalized = payload.normalize(input, new ProviderMetadata("offline", "offline", false), registry);
            result.put("normalizedSha256", hash(JSON.writeValueAsBytes(normalized)));
            result.put("bodySha256", hash(normalized.draft().optimizedPrompt()));
            step = "originalApplicationGuards"; var checked = shared.checkDraft(normalized.draft(), input);
            result.put("steps", checked.steps());
            if (checked.firstRejection() != null) { result.put("accepted", false); result.put("rejection", checked.firstRejection()); }
            else { result.put("accepted", true); result.put("assembledSha256", hash(checked.assembled())); }
        } catch (Throwable rejected) { result.put("accepted", false); result.put("stage", step); result.put("rejection", failure(rejected)); }
        return result;
    }

    private Map<String, Object> preparation(ObjectReader reader, JsonNode candidate, NaturalPromptInput input) {
        try {
            NaturalPromptRequirementRegistry.Preparation payload = reader.readValue(candidate);
            payload.resolve(input); return Map.of("accepted", true);
        } catch (Throwable rejected) { return Map.of("accepted", false, "rejection", failure(rejected)); }
    }

    /** Every mutation is a labelled copy; negative controls must fail rather than silently become successful originals. */
    private static void control(List<Map<String, Object>> controls, String id, boolean expected, Map<String, Object> actual) {
        require(Boolean.valueOf(expected).equals(actual.get("accepted")), "Unexpected isolated control result: " + id);
        Map<String, Object> item = new LinkedHashMap<>(); item.put("id", id); item.put("syntheticCopyControl", true);
        item.put("expectedAccepted", expected); item.put("actual", actual); controls.add(item);
    }

    private static NaturalPromptInput input(JsonNode trace) throws Exception {
        String user = trace.path("request").path("messages").get(1).path("content").asText();
        ObjectNode record = (ObjectNode) JSON.readTree(user.substring(user.indexOf('{')));
        record.retain(INPUT); var value = JSON.treeToValue(record, NaturalPromptInput.class);
        require(value.strategyVersion().equals("50"), "Not frozen50 input"); return value;
    }

    private JsonNode body(JsonNode trace) throws Throwable {
        require(trace.path("httpStatus").asInt() == 200 && trace.path("response").path("choices").get(0).path("finish_reason").asText().equals("stop"), "Trace is not complete200/stop");
        return shared.parseModelContent(trace.path("response").path("choices").get(0).path("message").path("content").asText());
    }

    /** Run against explicit locked preparation/first/repair files, with all compilation/output outside the stage and target. */
    public static void main(String[] args) throws Throwable {
        require(args.length == 6, "repo stage preparation first repair output required");
        Path repo = Path.of(args[0]).toAbsolutePath().normalize(), stage = Path.of(args[1]).toAbsolutePath().normalize(), output = Path.of(args[5]).toAbsolutePath().normalize();
        noHoldout(stage); noHoldout(stage.toRealPath()); noHoldout(output);
        require(stage.startsWith(repo) && output.startsWith(repo) && !output.startsWith(stage) && !Files.exists(output), "Unsafe/existing diagnosis output");
        JsonNode closure = read(stage.resolve("closure.json")); require(closure.path("status").asText().equals("CLOSED") && closure.path("entryLockedByClosure").asBoolean(), "Stage not closed/locked");
        Map<Path, String> hashes = new HashMap<>();
        for (JsonNode file : closure.path("files")) {
            Path path = repo.resolve(file.path("path").asText()).toAbsolutePath().normalize(); noHoldout(path);
            require(path.startsWith(stage) && path.toRealPath().startsWith(stage.toRealPath()), "Closed path escape");
            String sha = hash(Files.readAllBytes(path)); require(sha.equals(file.path("sha256").asText()), "Closed original hash mismatch"); hashes.put(path, sha);
        }
        Path prepPath = stage.resolve("traces").resolve(args[2]).normalize(), firstPath = stage.resolve("traces").resolve(args[3]).normalize(), repairPath = stage.resolve("traces").resolve(args[4]).normalize();
        require(hashes.containsKey(prepPath) && hashes.containsKey(firstPath) && hashes.containsKey(repairPath), "Selected originals are not closed");
        JsonNode prepTrace = read(prepPath), firstTrace = read(firstPath), repairTrace = read(repairPath);
        require(prepTrace.path("jobId").equals(firstTrace.path("jobId")) && firstTrace.path("jobId").equals(repairTrace.path("jobId")), "Selected originals have different jobs");
        var tool = new NaturalRegistry50ScalarDiagnosis();
        NaturalPromptInput input = input(firstTrace); require(input.equals(input(repairTrace)) && input.equals(input(prepTrace)), "Actual complete input changed");
        JsonNode prep = tool.body(prepTrace), first = tool.body(firstTrace), repair = tool.body(repairTrace);
        NaturalPromptRequirementRegistry.Preparation preparation = tool.strictPreparation.readValue(prep);
        var registry = preparation.resolve(input);
        String repairUser = repairTrace.path("request").path("messages").get(1).path("content").asText();
        JsonNode repairPayload = JSON.readTree(repairUser.substring(repairUser.indexOf('{')));
        require(repairPayload.path("preparedRequirements").equals(JSON.valueToTree(registry.requirements())), "Actual writer registry differs from actual preparation");
        require(repairTrace.path("registry49").path("preparedTrace").asText().equals(args[2]), "Repair points to another preparation");
        var legacyWriter = tool.copyReader(tool.strictWriter, NaturalPromptReferencedEnhancement.class, new NaturalPromptDiagnosticFieldHandler());
        var guardedWriter = tool.copyReader(tool.strictWriter, NaturalPromptReferencedEnhancement.class, new Guarded24());
        var guardedPreparation = tool.copyReader(tool.strictPreparation, NaturalPromptRequirementRegistry.Preparation.class, new Guarded24());
        var strictFirst = tool.writer(tool.strictWriter, first, input, registry); var strictRepair = tool.writer(tool.strictWriter, repair, input, registry);
        ObjectNode scalarRemoved = repair.deepCopy(); var coverage0 = (ObjectNode) scalarRemoved.path("coverage").get(0);
        require(coverage0.has("bodyQuotes_note") && coverage0.path("bodyQuotes_note").isTextual() && coverage0.path("bodyQuotes_note").asText().isEmpty(), "Expected isolated scalar is absent/different");
        coverage0.remove("bodyQuotes_note");
        var removedResult = tool.writer(tool.strictWriter, scalarRemoved, input, registry);
        var legacyResult = tool.writer(legacyWriter, repair, input, registry); var guardedResult = tool.writer(guardedWriter, repair, input, registry);
        require(Boolean.FALSE.equals(strictFirst.get("accepted")) && Boolean.FALSE.equals(strictRepair.get("accepted")) && Boolean.TRUE.equals(removedResult.get("accepted"))
                && Boolean.TRUE.equals(legacyResult.get("accepted")) && Boolean.TRUE.equals(guardedResult.get("accepted")), "Original defect isolation was not confirmed");
        require(removedResult.get("normalizedSha256").equals(legacyResult.get("normalizedSha256")) && removedResult.get("normalizedSha256").equals(guardedResult.get("normalizedSha256")), "Ignored scalar changed normalized data");
        List<Map<String, Object>> controls = new ArrayList<>();
        control(controls, "actual-first-with-guarded24-still-missing-bodyQuotes", false, tool.writer(guardedWriter, first, input, registry));
        for (String field : List.of("goal", "reqId", "replacesId", "requirementChanges", "index", "origin", "questions", "actualAnswers", "preparedRequirements", "actions", "inputReadiness")) {
            ObjectNode copy = repair.deepCopy(); ((ObjectNode) copy.path("coverage").get(0)).put(field, "untrusted");
            control(controls, "misplaced-protocol-or-server-field-" + field, false, tool.writer(guardedWriter, copy, input, registry));
        }
        ObjectNode old24Gap = repair.deepCopy(); ((ObjectNode) old24Gap.path("coverage").get(0)).put("goal", "untrusted");
        Map<String, Object> legacyGap = tool.writer(legacyWriter, old24Gap, input, registry);
        require(Boolean.TRUE.equals(legacyGap.get("accepted")), "Expected legacy24 semantic-field gap differs");
        for (String shape : List.of("object", "array", "sensitiveValue", "longValue", "longName", "sensitiveName")) {
            ObjectNode copy = repair.deepCopy();
            switch (shape) {
                case "object" -> copy.putObject("auditHint"); case "array" -> copy.putArray("auditHint");
                case "sensitiveValue" -> copy.put("auditHint", "sk-" + "A".repeat(40));
                case "longValue" -> copy.put("auditHint", "x".repeat(20_001));
                case "longName" -> copy.put("x".repeat(129), ""); default -> copy.put("sk-" + "A".repeat(40), "");
            }
            control(controls, "unknown-" + shape, false, tool.writer(guardedWriter, copy, input, registry));
        }
        for (String scalar : List.of("string", "boolean", "number", "null")) {
            ObjectNode copy = repair.deepCopy();
            switch (scalar) { case "string" -> copy.put("arbitraryDiagnostic", "bounded"); case "boolean" -> copy.put("arbitraryDiagnostic", true); case "number" -> copy.put("arbitraryDiagnostic", 1); default -> copy.putNull("arbitraryDiagnostic"); }
            control(controls, "unlisted-harmless-scalar-" + scalar, true, tool.writer(guardedWriter, copy, input, registry));
        }
        ObjectNode knownType = repair.deepCopy(); knownType.put("goal", 42);
        control(controls, "known-field-coercion-still-rejected", false, tool.writer(guardedWriter, knownType, input, registry));
        ObjectNode badSource = prep.deepCopy(); ((ObjectNode) badSource.path("requirements").get(0).path("evidence").get(0)).put("quote", "not in original source");
        control(controls, "known-source-quote-still-validated", false, tool.preparation(guardedPreparation, badSource, input));
        for (String field : List.of("reqId", "kind", "origin", "index")) {
            ObjectNode copy = prep.deepCopy(); ((ObjectNode) copy.path("requirements").get(0).path("evidence").get(0)).put(field, "untrusted");
            control(controls, "source-server-field-" + field, false, tool.preparation(guardedPreparation, copy, input));
        }
        ObjectNode wrongBodyQuote = repair.deepCopy(); ((ObjectNode) wrongBodyQuote.path("coverage").get(0)).putArray("bodyQuotes").add("not in body");
        control(controls, "raw-body-quote-still-validated", false, tool.writer(guardedWriter, wrongBodyQuote, input, registry));
        ObjectNode sensitiveBody = repair.deepCopy(); sensitiveBody.put("optimizedPrompt", sensitiveBody.path("optimizedPrompt").asText() + "sk-" + "A".repeat(40));
        control(controls, "original-body-safety-still-validates", false, tool.writer(guardedWriter, sensitiveBody, input, registry));
        var answers = new ArrayList<>(input.actualAnswers()); answers.add(new PlanAnswer("diagnosticCopy", "Synthetic bound-answer control", "未知"));
        var withAnswer = new NaturalPromptInput(input.rawPrompt(), input.context(), answers, input.conversation(), input.mandatoryRules(), input.modelId(), input.planningContext(), "50");
        control(controls, "synthetic-complete-input-answer-source-cannot-be-omitted", false, tool.preparation(guardedPreparation, prep, withAnswer));
        Map<String, Object> report = new LinkedHashMap<>(); report.put("modelCallsMade", 0); report.put("originalCandidateEdited", false); report.put("originalVerdictChanged", false);
        report.put("semanticCorrectnessClaimed", false); report.put("counterfactualOnly", true); report.put("jobId", firstTrace.path("jobId").asText());
        report.put("closureSha256", hash(Files.readAllBytes(stage.resolve("closure.json")))); report.put("sourceOriginals", List.of(Map.of("path", repo.relativize(prepPath).toString(), "sha256", hashes.get(prepPath)), Map.of("path", repo.relativize(firstPath).toString(), "sha256", hashes.get(firstPath)), Map.of("path", repo.relativize(repairPath).toString(), "sha256", hashes.get(repairPath))));
        report.put("strictActualFirst", strictFirst); report.put("strictActualRepair", strictRepair); report.put("strictCopyOnlyRemovedField", "/coverage/0/bodyQuotes_note");
        report.put("strictCopyOnlyRemovedScalar", removedResult); report.put("unchangedRepairWithFrozen24MapperCopy", legacyResult); report.put("unchangedRepairWithGuarded24MapperCopy", guardedResult);
        report.put("unchanged24MisplacedGoalGap", legacyGap); report.put("controls", controls); report.put("controlCount", controls.size());
        Map<String, String> classes = new LinkedHashMap<>();
        for (Class<?> type : List.of(OpenAiCompatibleNaturalPromptProvider.class, NaturalPromptDiagnosticFieldHandler.class, NaturalPromptReferencedEnhancement.class, NaturalPromptRequirementRegistry.class, NaturalTaskUnderstanding.class, NaturalTaskReview.class, NaturalEnhancementService.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { require(stream != null, "Frozen class missing"); classes.put(type.getName(), hash(stream.readAllBytes())); }
        }
        report.put("loadedFrozenProductClassSha256", classes);
        Files.createDirectories(output.getParent()); Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(JSON.writeValueAsString(Map.of("isolatedRepairAccepted", true, "actualFirstStillRejected", true, "controls", controls.size(), "modelCalls", 0, "originalVerdictChanged", false)));
    }
}
