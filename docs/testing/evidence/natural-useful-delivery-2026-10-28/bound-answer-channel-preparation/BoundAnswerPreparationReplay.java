import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parse only closed mechanism outputs with that stage's real product reader/resolve; the guard factory prohibits HTTP. */
public final class BoundAnswerPreparationReplay {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]), evidence = repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28");
        String stageName = args.length > 1 ? args[1] : "bound-answer-channel-prepare";
        if (!List.of("bound-answer-channel-prepare", "bound-question-hint-prepare", "native-pro52-prepare").contains(stageName)) throw new IllegalArgumentException("Finite closed mechanism stage required");
        Path stage = evidence.resolve(stageName), output = evidence.resolve(stageName.equals("bound-answer-channel-prepare")
                ? "bound-answer-channel-preparation/resolved" : stageName.equals("bound-question-hint-prepare")
                ? "bound-question-hint-preparation/resolved" : "native-pro52-preparation/resolved");
        JsonNode closure = JSON.readTree(stage.resolve("closure.json").toFile());
        if (!closure.path("status").asText().equals("CLOSED") || !closure.path("entryLockedByClosure").asBoolean()) throw new IllegalStateException("Closed originals required");
        Files.createDirectories(output);
        Method resolve = Class.forName("NaturalRegistry49CallGuard").getDeclaredMethod("normalizeActualPreparation", ObjectMapper.class, NaturalPromptInput.class, String.class);
        resolve.setAccessible(true); List<Object> results = new ArrayList<>();
        for (JsonNode job : JSON.readTree(stage.resolve("jobs.json").toFile())) {
            String id = job.path("id").asText(), user = job.path("approvedModelRequest").path("messages").path(1).path("content").asText();
            ObjectNode core = (ObjectNode) JSON.readTree(user.substring(user.indexOf('{')));
            core.remove(List.of("inputBoundary", "taskSources"));
            if (stageName.equals("bound-question-hint-prepare")) core.remove("boundQuestionHints");
            NaturalPromptInput input = JSON.treeToValue(core, NaturalPromptInput.class);
            Path resultFile = stage.resolve("results/" + id + ".json");
            if (!Files.exists(resultFile)) { results.add(Map.of("jobId", id, "status", "NOT_EXECUTED_BY_FROZEN_STOP_RULE", "actualModelCalls", 0)); continue; }
            JsonNode result = JSON.readTree(resultFile.toFile());
            String finish = result.path("body").path("data").path("finishReason").asText();
            if (result.path("status").asInt() != 200 || !finish.equals("stop")) {
                results.add(Map.of("jobId", id, "strictProductReaderAndResolve", false, "finishReason", finish)); continue;
            }
            try {
                Object prepared = resolve.invoke(null, JSON, input, result.path("body").path("data").path("content").asText());
                ObjectNode normalized = JSON.valueToTree(prepared);
                Files.write(output.resolve(job.path("caseId").asText() + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(normalized), StandardOpenOption.CREATE_NEW);
                results.add(Map.of("jobId", id, "strictProductReaderAndResolve", true, "finishReason", finish,
                        "requirements", normalized.path("requirements").size(), "actualModelCalls", 0));
            } catch (InvocationTargetException failure) {
                results.add(Map.of("jobId", id, "strictProductReaderAndResolve", false, "finishReason", finish,
                        "errorType", failure.getCause().getClass().getSimpleName(), "actualModelCalls", 0));
            }
        }
        JsonNode report = JSON.valueToTree(Map.of("status", "STRICT_PREPARATION_REPLAY_NOT_SEMANTIC_VERDICT", "actualModelCalls", 0,
                "stage", stageName, "usedClosedStageProductAndGuardRuntime", true, "results", results));
        Files.write(output.resolve("strict-reader-report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        System.out.println(JSON.writeValueAsString(report));
    }
}
