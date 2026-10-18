import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.*;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import com.promptoptimizer.provider.domain.PromptRewriteStrategy;
import java.nio.file.*;
import java.util.List;

/** 离线验证三次限定诊断的输入/分项/正式预算保护，不启动服务或预占真实请求。 */
public final class Candidate38DirectScopePreflight {
    static final ObjectMapper JSON = new ObjectMapper();
    static int checks;
    public static void main(String[] args) throws Exception {
        var cases = JSON.readTree(Path.of(args[0]).toFile());
        var batch = JSON.createObjectNode().put("version", "candidate38-live").put("sourceCandidate", "candidate38")
                .put("authorizationStatus", "APPROVED_BY_USER").put("qualityRetriesAllowed", false).put("status", "OPEN")
                .put("callsBefore", 412).put("candidateCallsBefore", 342).put("maximumNewCalls", 3);
        batch.putObject("purposeMaximums").put("PLANNING", 1).put("PLATFORM_GENERATION", 1).put("EXECUTION", 1).put("REVIEW", 0);
        var expected = batch.putArray("expectedEnhancementInputs");
        var sample = cases.get(0);
        var planView = EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false);
        var templateCode = TemplateCode.AUTO;
        var view = EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(), templateCode, List.of(), List.of(), false);
        var entry = expected.addObject().put("caseId", sample.path("id").asText()).put("rawPrompt", view.effectivePrompt())
                .put("planConfirmed", false).put("promptFormat", "COMPACT").put("templateCode", templateCode.name());
        entry.set("taskView", JSON.valueToTree(view.modelInput()));
        entry.set("planningTaskView", JSON.valueToTree(planView.modelInput()));
        entry.set("taskIntent", JSON.valueToTree(planView.intent()));
        entry.set("rewriteStrategy", JSON.createObjectNode().put("mode", PromptRewriteStrategy.forTask(view, PromptFormat.COMPACT).mode()));
        var payload = JSON.createObjectNode().put("rawPrompt", view.effectivePrompt()).put("planConfirmed", false);
        payload.putObject("template"); payload.putArray("conversationHistory"); payload.putArray("planAnswers"); payload.putArray("confirmedDecisions"); payload.putArray("ambiguities");
        payload.putObject("options").put("includeExamples", false).put("promptFormat", "COMPACT");
        payload.set("taskView", entry.path("taskView")); payload.set("rewriteStrategy", entry.path("rewriteStrategy"));
        check(allowed(payload, cases, batch), "exact enhancement");
        var changed = payload.deepCopy(); changed.put("planConfirmed", true); check(!allowed(changed, cases, batch), "fabricated Plan mode");
        changed = payload.deepCopy(); changed.put("rawPrompt", "different task"); check(!allowed(changed, cases, batch), "different task");
        changed = payload.deepCopy(); changed.set("taskView", JSON.createObjectNode()); check(!allowed(changed, cases, batch), "task view drift");
        changed = payload.deepCopy(); ((ObjectNode)changed.path("options")).put("includeExamples", true); check(!allowed(changed, cases, batch), "example drift");
        changed = payload.deepCopy(); changed.withArray("planAnswers").addObject().put("answer", "invented confirmation"); check(!allowed(changed, cases, batch), "invented answer");
        var plan = JSON.createObjectNode().put("rawPrompt", view.effectivePrompt()).put("contextDescription", "");
        plan.putArray("conversationHistory"); plan.putObject("questionScope"); plan.putArray("knownDecisions");
        plan.set("taskView", entry.path("planningTaskView")); plan.set("taskIntent", entry.path("taskIntent"));
        check(allowed(plan, cases, batch), "exact Plan");
        changed = plan.deepCopy(); changed.put("contextDescription", "foreign material"); check(!allowed(changed, cases, batch), "foreign description");
        changed = plan.deepCopy(); changed.withArray("knownDecisions").add("invented fact"); check(!allowed(changed, cases, batch), "invented known decision");
        changed = plan.deepCopy(); changed.set("taskIntent", JSON.createObjectNode()); check(!allowed(changed, cases, batch), "wrong intent");
        var request = new OptimizationRequest(view.effectivePrompt(), null, new EnhancementOptions(templateCode, false, true, false, PromptFormat.COMPACT),
                List.of(), PermissionPolicyInput.empty(), null);
        check(Candidate38EvaluationServer.requestAllowed(request, cases), "direct wrapper");
        var protocol = JSON.createObjectNode().put("maximumActualModelCalls", 425);
        protocol.putObject("phaseLimits").put("baseline", 80).put("candidateValidation", 345); protocol.set("activeBatch", batch);
        check(Candidate38EvaluationServer.approvedBatch(protocol).equals(batch), "current authorization");
        batch.put("status", "CLOSED"); check(rejected(protocol), "closed batch"); batch.put("status", "OPEN");
        batch.put("maximumNewCalls", 4); check(rejected(protocol), "wrong batch total"); batch.put("maximumNewCalls", 3);
        ((ObjectNode)batch.path("purposeMaximums")).put("PLANNING", 2); check(rejected(protocol), "wrong Plan cap");
        ((ObjectNode)batch.path("purposeMaximums")).put("PLANNING", 1);
        protocol.put("maximumActualModelCalls", 426); check(rejected(protocol), "wrong total cap"); protocol.put("maximumActualModelCalls", 425);
        ((ObjectNode)protocol.path("phaseLimits")).put("candidateValidation", 346); check(rejected(protocol), "wrong phase cap");
        ((ObjectNode)protocol.path("phaseLimits")).put("candidateValidation", 345).put("baseline", 81); check(rejected(protocol), "wrong baseline cap");
        batch.put("casesFile", "../foreign.json");
        boolean blocked = false; try { Candidate38EvaluationServer.frozenCases(batch); } catch (java.io.IOException error) { blocked = true; }
        check(blocked, "path traversal");
        var output = Path.of(args[1]);
        Files.writeString(output.resolveSibling("expected-inputs.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(expected));
        var result = JSON.createObjectNode().put("checks", checks).put("passed", checks).put("failed", 0).put("modelCalls", 0).put("budgetReservations", 0);
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result)); System.out.println(result);
    }
    private static boolean allowed(ObjectNode payload, JsonNode cases, JsonNode batch) throws Exception {
        var input = JSON.createObjectNode(); input.putArray("messages").addObject().put("role", "user").put("content", "fixture\n" + JSON.writeValueAsString(payload));
        return Candidate38EvaluationServer.inputAllowed(input, cases, batch);
    }
    private static boolean rejected(JsonNode protocol) { try { Candidate38EvaluationServer.approvedBatch(protocol); return false; } catch (java.io.IOException error) { return true; } }
    private static void check(boolean valid, String name) { if (!valid) throw new IllegalStateException(name); checks++; }
}
