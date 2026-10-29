import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalPromptSinglePassInstructions;
import java.nio.file.*;
import java.util.*;

/** Finite transport checks only; historical input is a unit fixture, never a fresh Plan or actual extraction. */
public class Single54GuardSelfTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static int passed;
    static ObjectNode request(ObjectNode input, String system) {
        return JSON.valueToTree(Map.of("messages", List.of(Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", JSON.valueToTree(input).toString()))));
    }
    static void check(String name, boolean reject, Path runtime, JsonNode protocol, JsonNode job, ObjectNode input, JsonNode request) throws Exception {
        boolean failed = false;
        try { NaturalRegistry49CallGuard.verify54(JSON, runtime, protocol, job, input, request); }
        catch (java.io.IOException expected) { failed = true; }
        if (failed != reject) throw new IllegalStateException("Unexpected finite guard result: " + name);
        passed++;
    }
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        JsonNode prior = JSON.readTree(repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28/registry52-plan-completion/traces/001.json").toFile());
        String user = prior.path("request").path("messages").path(1).path("content").asText();
        ObjectNode old = (ObjectNode) JSON.readTree(user.substring(user.indexOf('{')));
        old.remove(List.of("taskSources", "inputBoundary")); old.put("strategyVersion", "54");
        NaturalPromptInput typed = JSON.treeToValue(old, NaturalPromptInput.class);
        ObjectNode input = JSON.valueToTree(typed);
        input.set("inputBoundary", JSON.valueToTree(Map.of("stage", "PROMPT_OPTIMIZATION", "contextMeaning", "本次增强可见的材料，不是下游执行现场的完整资料或工具清单")));
        ObjectNode protocol = JSON.valueToTree(Map.of("modelId", "deepseek:deepseek-flash"));
        ObjectNode job = JSON.valueToTree(Map.of("id", "P02_CONFIRMED_V54_GEN", "version", "54", "phase", "GENERATION"));
        Path runtime = Files.createTempDirectory(repo.resolve("tmp"), "single54-guard-"); Files.createDirectory(runtime.resolve("traces"));
        String instruction = NaturalPromptSinglePassInstructions.ENHANCE;
        ObjectNode original = request(input, instruction);
        check("first-body", false, runtime, protocol, job, input, original);
        ObjectNode changed = input.deepCopy(); changed.putObject("taskScope");
        check("scope-rejected", true, runtime, protocol, job, changed, request(changed, instruction));
        changed = input.deepCopy(); changed.putArray("preparedRequirements");
        check("registry-rejected", true, runtime, protocol, job, changed, request(changed, instruction));
        changed = input.deepCopy(); changed.remove("inputBoundary");
        check("missing-boundary", true, runtime, protocol, job, changed, request(changed, instruction));
        changed = input.deepCopy(); changed.put("unexpected", "not a source");
        check("unknown-field", true, runtime, protocol, job, changed, request(changed, instruction));
        check("wrong-system", true, runtime, protocol, job, input, request(input, "wrong"));
        changed = input.deepCopy(); ((ObjectNode) changed.path("taskSources").path(0)).put("kind", "BOUND_PLAN_ANSWER");
        check("fake-source-kind", true, runtime, protocol, job, changed, request(changed, instruction));
        Files.writeString(runtime.resolve("traces/001.json"), JSON.writeValueAsString(Map.of("jobId", job.path("id").asText(), "request", original)));
        String repair = instruction + "\n上次响应未满足协议或必要保护。请重新按完整输入生成合法JSON并保留全部要求；每个字段只出现一次，不添加额外内容。";
        check("bounded-repair", false, runtime, protocol, job, input, request(input, repair));
        changed = input.deepCopy(); ((ObjectNode) changed.path("actualAnswers").path(0)).put("answer", "forged");
        check("answer-drift-on-repair", true, runtime, protocol, job, changed, request(changed, repair));
        check("repeat-first-system", true, runtime, protocol, job, input, original);
        Files.writeString(runtime.resolve("traces/002.json"), JSON.writeValueAsString(Map.of("jobId", job.path("id").asText(), "request", request(input, repair))));
        check("third-attempt", true, runtime, protocol, job, input, request(input, repair));
        System.out.println(JSON.writeValueAsString(Map.of("passed", passed, "actualModelCalls", 0,
                "fixtureOnly", true, "scope", "future54 finite transport; no product quality claim")));
    }
}
