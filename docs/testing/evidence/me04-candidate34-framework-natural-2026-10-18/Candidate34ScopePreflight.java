import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.*;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import com.promptoptimizer.provider.domain.PromptRewriteStrategy;
import java.nio.file.*;
import java.util.List;

/** 离线核对本批一题两模式、成对任务视图及预算入口，绝不预占或发送模型请求。 */
public final class Candidate34ScopePreflight {
    static final ObjectMapper JSON = new ObjectMapper();
    static int checks;
    public static void main(String[] args) throws Exception {
        var cases = JSON.readTree(Path.of(args[0]).toFile());
        var batch = JSON.createObjectNode();
        batch.put("version", "candidate34-live").put("sourceCandidate", "candidate34")
                .put("authorizationStatus", "APPROVED_BY_USER").put("qualityRetriesAllowed", false).put("status", "OPEN");
        batch.put("callsBefore", 381).put("candidateCallsBefore", 311).put("maximumNewCalls", 8);
        batch.putObject("purposeMaximums").put("PLATFORM_GENERATION", 3).put("EXECUTION", 3).put("REVIEW", 2);
        var expected = batch.putArray("expectedEnhancementInputs");
        for (var sample : cases) {
            var format = PromptFormat.valueOf(sample.path("promptFormat").asText());
            var view = EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(), TemplateCode.AUTO,
                    List.of(), List.of(), false);
            var entry = expected.addObject().put("caseId", sample.path("id").asText())
                    .put("rawPrompt", view.effectivePrompt()).put("planConfirmed", false).put("promptFormat", format.name());
            entry.set("taskView", JSON.valueToTree(view.modelInput()));
            entry.set("rewriteStrategy", JSON.valueToTree(PromptRewriteStrategy.forTask(view, format)));
            check(Candidate34EvaluationServer.requestAllowed(request(sample, format, false, false), cases), "frozen request");
            check(!Candidate34EvaluationServer.requestAllowed(request(sample, null, false, false), cases), "explicit mode required");
            check(!Candidate34EvaluationServer.requestAllowed(request(sample, format, true, false), cases), "changed examples");
            check(!Candidate34EvaluationServer.requestAllowed(request(sample, format, false, true), cases), "Plan expansion rejected");
        }
        for (var entry : expected) {
            var payload = JSON.createObjectNode();
            payload.put("rawPrompt", entry.path("rawPrompt").asText());
            payload.putObject("template");
            payload.putObject("options").put("includeExamples", false).set("promptFormat", entry.path("promptFormat"));
            payload.put("planConfirmed", false);
            payload.set("taskView", entry.path("taskView"));
            payload.set("rewriteStrategy", entry.path("rewriteStrategy"));
            check(allowed(payload, cases, batch), "matched raw/view/mode/strategy");
            var changed = payload.deepCopy(); changed.put("rawPrompt", "unapproved task");
            check(!allowed(changed, cases, batch), "unknown task");
            changed = payload.deepCopy(); changed.set("taskView", JSON.createObjectNode());
            check(!allowed(changed, cases, batch), "wrong task view");
            changed = payload.deepCopy(); ((ObjectNode) changed.path("options")).remove("promptFormat");
            check(!allowed(changed, cases, batch), "stripped generation mode");
            changed = payload.deepCopy(); ((ObjectNode) changed.path("options")).put("promptFormat", "UNKNOWN");
            check(!allowed(changed, cases, batch), "invalid mode");
            changed = payload.deepCopy(); changed.set("rewriteStrategy", JSON.createObjectNode());
            check(!allowed(changed, cases, batch), "strategy must reach model");
            changed = payload.deepCopy(); changed.put("planConfirmed", true);
            check(!allowed(changed, cases, batch), "fabricated confirmation");
            changed = payload.deepCopy(); ((ObjectNode) changed.path("options")).put("includeExamples", true);
            check(!allowed(changed, cases, batch), "unauthorized example switch");
        }
        var plan = JSON.createObjectNode().put("rawPrompt", cases.get(0).path("rawPrompt").asText());
        plan.putObject("questionScope"); plan.putObject("taskIntent"); plan.putArray("knownDecisions");
        check(!allowed(plan, cases, batch), "no natural Plan requests");
        var protocol = JSON.createObjectNode().put("maximumActualModelCalls", 399);
        protocol.putObject("phaseLimits").put("baseline", 80).put("candidateValidation", 319);
        protocol.set("activeBatch", batch);
        check(Candidate34EvaluationServer.approvedBatch(protocol).equals(batch), "authorized batch");
        batch.put("status", "CLOSED"); check(rejected(protocol), "closed batch"); batch.put("status", "OPEN");
        batch.put("sourceCandidate", "other"); check(rejected(protocol), "wrong frozen source"); batch.put("sourceCandidate", "candidate34");
        batch.put("authorizationStatus", "PENDING"); check(rejected(protocol), "missing approval"); batch.put("authorizationStatus", "APPROVED_BY_USER");
        batch.put("casesFile", "../request-cases.json");
        boolean pathRejected = false;
        try { Candidate34EvaluationServer.frozenCases(batch); } catch (java.io.IOException exception) { pathRejected = true; }
        check(pathRejected, "path traversal");
        var output = Path.of(args[1]);
        Files.writeString(output.resolveSibling("expected-inputs.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(expected));
        var result = JSON.createObjectNode().put("checks", checks).put("passed", checks).put("failed", 0)
                .put("externalModelCalls", 0).put("springStarted", false).put("budgetReservationAttempted", false);
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println(result);
    }
    private static OptimizationRequest request(JsonNode sample, PromptFormat format, boolean examples, boolean plan) {
        return new OptimizationRequest(sample.path("rawPrompt").asText(), null,
                new EnhancementOptions(TemplateCode.AUTO, false, true, examples, format), List.of(), PermissionPolicyInput.empty(),
                plan ? new PlanConfirmation("offline", null, List.of()) : null);
    }
    private static boolean allowed(ObjectNode payload, JsonNode cases, JsonNode batch) throws Exception {
        var input = JSON.createObjectNode(); input.putArray("messages").addObject().put("role", "user")
                .put("content", "fixture\n" + JSON.writeValueAsString(payload));
        return Candidate34EvaluationServer.inputAllowed(input, cases, batch);
    }
    private static boolean rejected(JsonNode protocol) {
        try { Candidate34EvaluationServer.approvedBatch(protocol); return false; }
        catch (java.io.IOException exception) { return true; }
    }
    private static void check(boolean valid, String label) {
        if (!valid) throw new IllegalStateException("Scope failed: " + label);
        checks++;
    }
}
