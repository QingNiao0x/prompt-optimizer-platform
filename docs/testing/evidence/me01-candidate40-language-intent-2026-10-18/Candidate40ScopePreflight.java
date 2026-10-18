import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import com.promptoptimizer.provider.domain.PromptRewriteStrategy;
import java.nio.file.*;
import java.util.List;

/** 不发请求、不占额度，验证冻结输入、真实题答绑定边界和正式预算保护。 */
public final class Candidate40ScopePreflight {
    static final ObjectMapper JSON = new ObjectMapper();
    static int checks;

    /** 仅创建离线探针报告，实际增强必须另读已提交的服务端题答锁。 */
    public static void main(String[] args) throws Exception {
        var cases = JSON.readTree(Path.of(args[0]).toFile());
        var batch = JSON.createObjectNode().put("version", "candidate40-live").put("sourceCandidate", "candidate40")
                .put("authorizationStatus", "APPROVED_BY_USER").put("qualityRetriesAllowed", false).put("status", "OPEN")
                .put("callsBefore", 415).put("candidateCallsBefore", 345).put("maximumNewCalls", 13);
        batch.putObject("purposeMaximums").put("PLANNING", 6).put("PLATFORM_GENERATION", 3).put("EXECUTION", 2).put("REVIEW", 2);
        var expected = JSON.createArrayNode();
        for (var sample : cases) {
            var view = EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false);
            var plan = JSON.createObjectNode().put("rawPrompt", sample.path("rawPrompt").asText()).put("contextDescription", "");
            plan.putArray("conversationHistory"); plan.putObject("questionScope"); plan.putArray("knownDecisions");
            plan.set("taskView", JSON.valueToTree(view.modelInput())); plan.set("taskIntent", JSON.valueToTree(view.intent()));
            check(allowed(plan, cases, List.of()), "exact natural Plan");
            var changed = plan.deepCopy(); changed.put("rawPrompt", "foreign task"); check(!allowed(changed, cases, List.of()), "foreign raw");
            changed = plan.deepCopy(); changed.put("contextDescription", "foreign material"); check(!allowed(changed, cases, List.of()), "foreign description");
            changed = plan.deepCopy(); changed.withArray("knownDecisions").add("invented"); check(!allowed(changed, cases, List.of()), "invented confirmation");
            changed = plan.deepCopy(); changed.set("taskIntent", JSON.createObjectNode()); check(!allowed(changed, cases, List.of()), "wrong identity");
            expected.addObject().put("caseId", sample.path("id").asText()).set("planningTaskView", JSON.valueToTree(view.modelInput()));
        }
        var sample = cases.get(0);
        List<PlanAnswer> answers = List.of(new PlanAnswer("bound-question", "文本按什么粒度去重？", "按整行完全相同去重，区分大小写，保留第一次出现的顺序"));
        var template = new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl().infer(sample.path("rawPrompt").asText());
        var view = EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(), template, answers, List.of(), false);
        var payload = JSON.createObjectNode().put("rawPrompt", sample.path("rawPrompt").asText()).put("planConfirmed", true);
        payload.putObject("template"); payload.putArray("conversationHistory"); payload.set("planAnswers", JSON.valueToTree(answers));
        payload.putObject("options").put("promptFormat", "COMPACT").put("includeExamples", false);
        payload.set("taskView", JSON.valueToTree(view.modelInput()));
        payload.putObject("rewriteStrategy").put("mode", PromptRewriteStrategy.forTask(view, PromptFormat.COMPACT).mode());
        check(allowed(payload, cases, answers), "bound input view");
        var changed = payload.deepCopy(); changed.putArray("planAnswers"); check(!allowed(changed, cases, answers), "omitted answer");
        changed = payload.deepCopy(); changed.set("taskView", JSON.createObjectNode()); check(!allowed(changed, cases, answers), "view drift");
        changed = payload.deepCopy(); changed.put("planConfirmed", false); check(!allowed(changed, cases, answers), "not Plan");
        changed = payload.deepCopy(); ((ObjectNode) changed.path("options")).put("includeExamples", true); check(!allowed(changed, cases, answers), "example drift");
        changed = payload.deepCopy(); changed.put("rawPrompt", cases.get(1).path("rawPrompt").asText()); check(!allowed(changed, cases, answers), "diagnostic-only task cannot enhance");
        var protocol = JSON.createObjectNode().put("maximumActualModelCalls", 438);
        protocol.putObject("phaseLimits").put("baseline", 80).put("candidateValidation", 358); protocol.set("activeBatch", batch);
        check(Candidate40EvaluationServer.approvedBatch(protocol).equals(batch), "approved scope");
        batch.put("status", "CLOSED"); check(rejected(protocol), "closed batch"); batch.put("status", "OPEN");
        batch.put("maximumNewCalls", 14); check(rejected(protocol), "batch cap"); batch.put("maximumNewCalls", 13);
        ((ObjectNode) batch.path("purposeMaximums")).put("PLANNING", 7); check(rejected(protocol), "purpose cap");
        ((ObjectNode) batch.path("purposeMaximums")).put("PLANNING", 6);
        protocol.put("maximumActualModelCalls", 439); check(rejected(protocol), "total cap"); protocol.put("maximumActualModelCalls", 438);
        ((ObjectNode) protocol.path("phaseLimits")).put("candidateValidation", 359); check(rejected(protocol), "phase cap");
        ((ObjectNode) protocol.path("phaseLimits")).put("candidateValidation", 358).put("baseline", 81); check(rejected(protocol), "baseline cap");
        batch.put("casesFile", "../foreign.json"); boolean blocked = false;
        try { Candidate40EvaluationServer.frozenCases(batch); } catch (java.io.IOException error) { blocked = true; }
        check(blocked, "path traversal");
        var output = Path.of(args[1]);
        Files.writeString(output.resolveSibling("expected-inputs.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(expected));
        var result = JSON.createObjectNode().put("checks", checks).put("passed", checks).put("failed", 0).put("modelCalls", 0).put("budgetReservations", 0);
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result)); System.out.println(result);
    }

    /** 将合成业务载荷装入与真实客户端相同的用户消息，保持原数据。 */
    private static boolean allowed(ObjectNode payload, JsonNode cases, List<PlanAnswer> answers) throws Exception {
        var input = JSON.createObjectNode(); input.putArray("messages").addObject().put("role", "user").put("content", "fixture\n" + JSON.writeValueAsString(payload));
        return Candidate40EvaluationServer.scopedInputAllowed(input, cases, answers);
    }

    /** 错误批准信息必须拒绝，探针不得通过预占验证预算。 */
    private static boolean rejected(JsonNode protocol) {
        try { Candidate40EvaluationServer.approvedBatch(protocol); return false; } catch (java.io.IOException error) { return true; }
    }

    /** 首个工具保护失败即停止本轮准备。 */
    private static void check(boolean valid, String name) { if (!valid) throw new IllegalStateException(name); checks++; }
}
