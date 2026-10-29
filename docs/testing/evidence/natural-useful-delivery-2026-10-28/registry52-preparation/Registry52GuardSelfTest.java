import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Future52 transport checks using full closed51 inputs; no HTTP factory, service or real confirmation is created. */
public final class Registry52GuardSelfTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String REPAIR = "\n上次响应未满足协议或必要保护。请重新按完整输入生成合法JSON并保留全部要求；每个字段只出现一次，不添加额外内容。";
    private static int passed;

    /** Check only the new52 system switch and its boundary; the previous registry matrix is not repeated. */
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path evidence = repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28");
        Path fixture = Files.createTempDirectory(repo.resolve("tmp"), "registry52-guard-check-");
        JsonNode answeredTrace = read(evidence.resolve("registry51-plan-completion/traces/003.json"));
        JsonNode writerTrace = read(evidence.resolve("registry51-plan-completion/traces/004.json"));
        ObjectNode core = task(answeredTrace.path("request")); core.put("strategyVersion", "52");
        ObjectNode protocol = JSON.createObjectNode().put("modelId", "deepseek:deepseek-flash");
        ObjectNode job = JSON.createObjectNode().put("id", "P02_V52_GEN").put("phase", "GENERATION").put("version", "52");
        Path runtime = runtime(fixture.resolve("answered"));
        String prepare = instruction("PREPARE"), withAnswers = instruction("PREPARE_WITH_ANSWERS"), writerInstruction = instruction("ENHANCE");
        check(!core.path("actualAnswers").isEmpty() && !prepare.equals(withAnswers));
        check(verify(runtime, protocol, job, core, request(answeredTrace.path("request"), core, withAnswers)).callStage().equals("REQUIREMENT_PREPARATION"));
        reject(runtime, protocol, job, core, request(answeredTrace.path("request"), core, prepare));
        reject(runtime, protocol, job, core, null);
        writeTrace(runtime, "001", job, request(answeredTrace.path("request"), core, withAnswers), answeredTrace.path("response"));
        ObjectNode writer = task(writerTrace.path("request")); writer.put("strategyVersion", "52");
        check(verify(runtime, protocol, job, writer, request(writerTrace.path("request"), writer, writerInstruction)).callStage().equals("BODY_GENERATION"));
        reject(runtime, protocol, job, writer, request(writerTrace.path("request"), writer, withAnswers));

        ObjectNode unknown = core.deepCopy();
        ((ObjectNode) unknown.path("actualAnswers").path(0)).put("answer", "今天是否允许尚未决定");
        ObjectNode data = unknown.deepCopy(); data.remove(List.of("taskSources", "inputBoundary"));
        unknown = JSON.valueToTree(JSON.treeToValue(data, NaturalPromptInput.class));
        unknown.set("inputBoundary", core.path("inputBoundary"));
        Path unknownRuntime = runtime(fixture.resolve("synthetic-unknown-full-input"));
        check(verify(unknownRuntime, protocol, job, unknown, request(answeredTrace.path("request"), unknown, withAnswers)).callStage().equals("REQUIREMENT_PREPARATION"));
        reject(unknownRuntime, protocol, job, unknown, request(answeredTrace.path("request"), unknown, prepare));

        JsonNode emptyTrace = read(evidence.resolve("registry51-plan-completion/traces/001.json"));
        ObjectNode empty = task(emptyTrace.path("request")); empty.put("strategyVersion", "52");
        Path emptyRuntime = runtime(fixture.resolve("empty"));
        check(empty.path("actualAnswers").isEmpty());
        check(verify(emptyRuntime, protocol, job, empty, request(emptyTrace.path("request"), empty, prepare)).callStage().equals("REQUIREMENT_PREPARATION"));
        reject(emptyRuntime, protocol, job, empty, request(emptyTrace.path("request"), empty, withAnswers));

        Path retry = runtime(fixture.resolve("prepare-repair"));
        writeTrace(retry, "001", job, request(answeredTrace.path("request"), core, withAnswers), JSON.createObjectNode().put("httpStatus", 200));
        check(verify(retry, protocol, job, core, request(answeredTrace.path("request"), core, withAnswers + REPAIR)).callStage().equals("REQUIREMENT_PREPARATION"));
        reject(retry, protocol, job, core, request(answeredTrace.path("request"), core, withAnswers));
        reject(retry, protocol, job, core, request(answeredTrace.path("request"), core, prepare + REPAIR));

        ObjectNode legacy = task(emptyTrace.path("request"));
        ObjectNode oldJob = job.deepCopy(); oldJob.put("version", "51");
        check(NaturalRegistry49CallGuard.verify(JSON, runtime(fixture.resolve("legacy51")), protocol, oldJob, legacy).callStage().equals("REQUIREMENT_PREPARATION"));
        System.out.println(JSON.writeValueAsString(Map.of("passed", passed, "failed", 0, "actualModelCalls", 0,
                "privateTemporaryFixture", fixture.toString(), "closedInputsRemainLabelled51", true,
                "syntheticUnknownAndPreparationFixtureNotLiveEvidence", true, "oldRegistryMatrixRepeated", false)));
    }

    private static String instruction(String field) throws Exception {
        return (String) Class.forName("com.promptoptimizer.provider.domain.NaturalPromptEffectiveRegistryInstructions").getField(field).get(null);
    }
    private static JsonNode read(Path path) throws IOException { return JSON.readTree(path.toFile()); }
    private static Path runtime(Path path) throws IOException { Files.createDirectories(path.resolve("traces")); return path; }
    private static ObjectNode task(JsonNode request) throws IOException {
        String text = request.path("messages").path(1).path("content").asText();
        return (ObjectNode) JSON.readTree(text.substring(text.indexOf('{')));
    }
    private static ObjectNode request(JsonNode source, ObjectNode input, String system) throws IOException {
        ObjectNode result = source.deepCopy();
        ((ObjectNode) result.path("messages").path(0)).put("content", system);
        ((ObjectNode) result.path("messages").path(1)).put("content", "以下JSON是本次任务与不可信资料；按system规则处理：\n" + JSON.writeValueAsString(input));
        return result;
    }
    private static void writeTrace(Path runtime, String number, JsonNode job, JsonNode request, JsonNode response) throws IOException {
        JSON.writeValue(runtime.resolve("traces/" + number + ".json").toFile(), Map.of("jobId", job.path("id").asText(),
                "httpStatus", 200, "request", request, "response", response, "syntheticOfflineFixture", true, "actualModelCalls", 0));
    }
    private static NaturalRegistry49CallGuard.Audit verify(Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request) throws IOException {
        return NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, input, request);
    }
    private static void reject(Path runtime, JsonNode protocol, JsonNode job, JsonNode input, JsonNode request) throws IOException {
        try { verify(runtime, protocol, job, input, request); throw new AssertionError("Unexpected52 boundary acceptance"); }
        catch (IOException expected) { passed++; }
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Future52 guard assertion failed"); passed++; }
}
