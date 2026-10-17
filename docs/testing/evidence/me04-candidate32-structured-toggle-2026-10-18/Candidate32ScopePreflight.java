import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.PlanConfirmation;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 纯本地作用域正反例；不启动Spring、不预占调用、不读取账号、不发送网络请求。 */
public final class Candidate32ScopePreflight {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int checks;

    /** 使用冻结案例核对开关、输入成对绑定、Plan范围及批次状态保护。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("casesPath, resultPath required");
        var cases = JSON.readTree(Path.of(args[0]).toFile());
        var batch = JSON.createObjectNode();
        batch.put("version", "candidate32-live"); batch.put("sourceCandidate", "candidate32");
        batch.put("authorizationStatus", "APPROVED_BY_USER"); batch.put("secondRoundAuthorized", true);
        batch.put("status", "OPEN");
        var expected = batch.putArray("expectedEnhancementInputs");
        for (var sample : cases) {
            var format = PromptFormat.valueOf(sample.path("promptFormat").asText());
            var request = request(sample, format, sample.path("includeExamples").asBoolean());
            check(Candidate32EvaluationServer.requestAllowed(request, cases), "approved explicit case");
            check(!Candidate32EvaluationServer.requestAllowed(request(sample,
                    format == PromptFormat.COMPACT ? PromptFormat.STRUCTURED : PromptFormat.COMPACT, false), cases), "wrong explicit format");
            check(!Candidate32EvaluationServer.requestAllowed(request(sample, null, false), cases), "missing explicit format");
            check(!Candidate32EvaluationServer.requestAllowed(request(sample, format, true), cases), "changed example switch");
            var view = EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(), TemplateCode.AUTO,
                    List.of(), List.of(), sample.path("includeExamples").asBoolean());
            if (!"natural-plan".equals(sample.path("mode").asText())) {
                expected.addObject().put("caseId", sample.path("id").asText()).put("rawPrompt", view.effectivePrompt())
                        .put("planConfirmed", false).set("taskView", JSON.valueToTree(view.modelInput()));
            }
        }
        var direct = cases.get(0);
        var directView = expected.get(0).path("taskView");
        var payload = JSON.createObjectNode();
        payload.put("rawPrompt", direct.path("rawPrompt").asText()); payload.putObject("template");
        payload.putObject("options").put("includeExamples", false); payload.put("planConfirmed", false);
        payload.set("taskView", directView);
        check(allowed(payload, cases, batch), "exact raw and task view accepted");
        var changed = payload.deepCopy(); changed.put("rawPrompt", "unapproved fixture task");
        check(!allowed(changed, cases, batch), "unknown raw rejected");
        changed = payload.deepCopy(); changed.set("taskView", JSON.createObjectNode());
        check(!allowed(changed, cases, batch), "changed task view rejected");
        changed = payload.deepCopy(); changed.set("taskView", expected.get(1).path("taskView"));
        check(!allowed(changed, cases, batch), "task views cannot cross cases");
        changed = payload.deepCopy(); changed.put("planConfirmed", true);
        check(!allowed(changed, cases, batch), "fabricated confirmation rejected");
        changed = payload.deepCopy(); ((ObjectNode) changed.path("options")).put("includeExamples", true);
        check(!allowed(changed, cases, batch), "provider example switch rejected");
        changed = payload.deepCopy(); ((ObjectNode) changed.path("options")).put("promptFormat", "COMPACT");
        check(!allowed(changed, cases, batch), "display preference cannot reach model");
        var plan = JSON.createObjectNode(); plan.put("rawPrompt", cases.get(2).path("rawPrompt").asText());
        plan.put("questionScope", "fixture"); plan.putObject("taskIntent"); plan.putArray("knownDecisions");
        check(allowed(plan, cases, batch), "natural Plan case accepted");
        plan.put("rawPrompt", direct.path("rawPrompt").asText());
        check(!allowed(plan, cases, batch), "direct case cannot expand into Plan sampling");
        var protocol = JSON.createObjectNode(); protocol.set("activeBatch", batch);
        check(Candidate32EvaluationServer.approvedBatch(protocol).equals(batch), "approved candidate accepted");
        batch.put("status", "CLOSED"); check(rejects(protocol), "closed batch rejected");
        batch.put("status", "OPEN"); batch.put("sourceCandidate", "other");
        check(rejects(protocol), "wrong frozen source rejected"); batch.put("sourceCandidate", "candidate32");
        batch.put("authorizationStatus", "PENDING"); check(rejects(protocol), "missing approval rejected");
        batch.put("authorizationStatus", "APPROVED_BY_USER"); batch.put("casesFile", "../live-cases.json");
        boolean pathRejected = false;
        try { Candidate32EvaluationServer.frozenCases(batch); } catch (java.io.IOException expectedError) { pathRejected = true; }
        check(pathRejected, "path traversal rejected");
        var result = JSON.createObjectNode(); result.put("checks", checks); result.put("passed", checks);
        result.put("failed", 0); result.put("externalModelCalls", 0); result.put("springStarted", false);
        result.put("accountsReadOrCreated", false); result.put("budgetReservationAttempted", false);
        Files.writeString(Path.of(args[1]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println(JSON.writeValueAsString(result));
    }

    private static OptimizationRequest request(JsonNode sample, PromptFormat format, boolean examples) {
        return new OptimizationRequest(sample.path("rawPrompt").asText(), null,
                new EnhancementOptions(TemplateCode.AUTO, false, true, examples, format), List.of(),
                PermissionPolicyInput.empty(), "natural-plan".equals(sample.path("mode").asText())
                        ? new PlanConfirmation("offline-scope-fixture", null, List.of()) : null);
    }

    private static boolean allowed(ObjectNode payload, JsonNode cases, JsonNode batch) throws Exception {
        var input = JSON.createObjectNode(); input.putArray("messages").addObject()
                .put("role", "user").put("content", "offline fixture\n" + JSON.writeValueAsString(payload));
        return Candidate32EvaluationServer.inputAllowed(input, cases, batch);
    }

    private static boolean rejects(JsonNode protocol) {
        try { Candidate32EvaluationServer.approvedBatch(protocol); return false; }
        catch (java.io.IOException expected) { return true; }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("Scope preflight failed: " + name);
        checks++;
    }
}
